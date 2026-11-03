package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Receives the gateway's webhooks (billing design, section 5, P4):
 * <ol>
 *   <li>the signature is checked over the exact body before anything in it is read;</li>
 *   <li>each event id is stored once, so a repeated delivery changes nothing;</li>
 *   <li>the event is applied in the same transaction as its record. If applying fails, the event is kept as
 *   {@code FAILED} for a platform administrator to replay, and the gateway still gets a success so it does not resend
 *   it for days.</li>
 * </ol>
 */
@Service
public class WebhookService {
    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final SubscriptionService subscriptions;
    private final PlatformAudit platformAudit;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public WebhookService(JdbcClient jdbc, PaymentGateway gateway, SubscriptionService subscriptions, PlatformAudit platformAudit, ObjectMapper json,
                          PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.subscriptions = subscriptions;
        this.platformAudit = platformAudit;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    /** Returns the stored outcome: APPLIED, IGNORED, UNMATCHED, FAILED, or DUPLICATE for an event seen before. */
    public String receive(String gatewayName, String eventIdHeader, String signature, String body) {
        if (!gateway.name().equals(gatewayName)) throw ApiException.notFound("NOT_FOUND", "Not found.");
        if (!gateway.validSignature(body, signature)) {
            log.warn("A {} webhook with a bad signature was refused", gatewayName);
            throw ApiException.badRequest("WEBHOOK_SIGNATURE_INVALID", "The signature does not match.");
        }
        GatewayEvent event;
        try {
            event = GatewayEvent.parse(json, eventIdHeader, body);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("WEBHOOK_UNREADABLE", "The event could not be read.");
        }
        try {
            return tx.execute(status -> {
                Optional<Long> stored = jdbc.sql("""
                                insert into sf_gateway_events (gateway, event_id, event_type, payload, outcome) values (:g, :e, :t, cast(:p as jsonb), 'UNMATCHED')
                                on conflict (gateway, event_id) do nothing returning id""")
                        .param("g", gateway.name()).param("e", event.eventId()).param("t", event.type()).param("p", body).query(Long.class).optional();
                if (stored.isEmpty()) return "DUPLICATE";
                String outcome = subscriptions.apply(event, stored.get());
                jdbc.sql("update sf_gateway_events set outcome = :o, processed_at = now() where id = :id").param("o", outcome).param("id", stored.get()).update();
                return outcome;
            });
        } catch (RuntimeException e) {
            log.error("Applying {} event {} failed; it is kept for a replay", gatewayName, event.eventId(), e);
            jdbc.sql("""
                            insert into sf_gateway_events (gateway, event_id, event_type, payload, outcome, error) values (:g, :e, :t, cast(:p as jsonb), 'FAILED', :err)
                            on conflict (gateway, event_id) do nothing""")
                    .param("g", gateway.name()).param("e", event.eventId()).param("t", event.type()).param("p", body)
                    .param("err", e.getClass().getSimpleName()).update();
            return "FAILED";
        }
    }

    public record StoredEvent(long id, String eventId, String eventType, Long tenantId, Instant receivedAt, Instant processedAt, String outcome, String error) {}

    /** Events that did not apply: failed, or about a subscription we do not know. */
    public List<StoredEvent> unapplied() {
        return jdbc.sql("""
                        select id, event_id, event_type, customer_tenant_id, received_at, processed_at, outcome, error from sf_gateway_events
                        where outcome in ('FAILED', 'UNMATCHED') order by received_at desc limit 100""")
                .query((rs, i) -> new StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8))).list();
    }

    /** Applies a stored event again (P12), for example once the cause of a failure is fixed. */
    public StoredEvent replay(CurrentUser admin, long id) {
        Map<String, Object> row = jdbc.sql("select event_id, payload::text as payload, outcome from sf_gateway_events where id = :id").param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("EVENT_NOT_FOUND", "Event not found."));
        if (!List.of("FAILED", "UNMATCHED").contains((String) row.get("outcome"))) throw ApiException.conflict("EVENT_ALREADY_APPLIED", "This event was already applied.");
        GatewayEvent event = GatewayEvent.parse(json, (String) row.get("event_id"), (String) row.get("payload"));
        String outcome = tx.execute(status -> {
            String o = subscriptions.apply(event, id);
            jdbc.sql("update sf_gateway_events set outcome = :o, processed_at = now(), error = null where id = :id").param("o", o).param("id", id).update();
            return o;
        });
        platformAudit.record(admin.id(), "BILLING_EVENT_REPLAYED", "OPS", id, Map.of("event", event.eventId(), "outcome", outcome));
        return unappliedOrApplied(id);
    }

    private StoredEvent unappliedOrApplied(long id) {
        return jdbc.sql("select id, event_id, event_type, customer_tenant_id, received_at, processed_at, outcome, error from sf_gateway_events where id = :id")
                .param("id", id).query((rs, i) -> new StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), rs.getTimestamp(5).toInstant(),
                        rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant(), rs.getString(7), rs.getString(8))).single();
    }
}
