package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.mail.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The nightly reconciliation (billing design, P12): the gateway's captured payments of the last 3 days against ours,
 * and the gateway's state of every running subscription against ours. It never changes anything; differences are
 * listed in the console and sent as an alert, and an administrator replays the missing event.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final Duration WINDOW = Duration.ofDays(3);

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final EmailSender email;
    private final ObjectMapper json;
    private final Clock clock;
    private final String alertEmail;

    public ReconciliationService(JdbcClient jdbc, PaymentGateway gateway, EmailSender email, ObjectMapper json, Clock clock,
                                 @Value("${app.ops.alert-email:}") String alertEmail) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.email = email;
        this.json = json;
        this.clock = clock;
        this.alertEmail = alertEmail == null ? "" : alertEmail.trim();
    }

    public record Difference(String kind, String gatewayId, Long tenantId, String detail) {}

    public record Run(long id, Instant ranAt, Instant windowFrom, Instant windowTo, boolean ok, List<Difference> differences, String error) {}

    @Scheduled(cron = "${app.jobs.reconciliation-cron:0 45 2 * * *}")
    public void scheduled() { run(clock.instant()); }

    public Run runNow() { return run(clock.instant()); }

    public Run run(Instant now) {
        Instant from = now.minus(WINDOW);
        List<Difference> diffs = new ArrayList<>();
        String error = null;
        try {
            for (PaymentGateway.GatewayPayment p : gateway.payments(from, now)) {
                boolean known = jdbc.sql("select count(*) from sf_payments where gateway = :g and gateway_payment_id = :p")
                        .param("g", gateway.name()).param("p", p.paymentId()).query(Long.class).single() > 0;
                if (!known) {
                    Long tenant = p.subscriptionId() == null ? null : jdbc.sql("""
                                    select tenant_id from sf_subscriptions where gateway = :g and (gateway_subscription_id = :s or checkout_gateway_subscription_id = :s)""")
                            .param("g", gateway.name()).param("s", p.subscriptionId()).query(Long.class).optional().orElse(null);
                    diffs.add(new Difference("PAYMENT_NOT_RECORDED", p.paymentId(), tenant, p.amount().toPlainString()));
                }
            }
            List<Map<String, Object>> running = jdbc.sql("""
                            select tenant_id, gateway_subscription_id, state from sf_subscriptions
                            where gateway = :g and gateway_subscription_id is not null and state in ('ACTIVE', 'PAST_DUE', 'CANCELLED')""")
                    .param("g", gateway.name()).query().listOfRows();
            for (Map<String, Object> r : running) {
                String id = (String) r.get("gateway_subscription_id");
                String ours = (String) r.get("state");
                Optional<String> theirs = gateway.subscriptionState(id);
                if (theirs.isEmpty() || !theirs.get().equals(ours)) {
                    diffs.add(new Difference("STATE_DIFFERS", id, ((Number) r.get("tenant_id")).longValue(), "ours " + ours + ", gateway " + theirs.orElse("unknown")));
                }
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName();
            log.error("The billing reconciliation failed", e);
        }
        boolean ok = error == null && diffs.isEmpty();
        long id = jdbc.sql("insert into sf_billing_reconciliations (window_from, window_to, ok, differences, error) values (:f, :t, :ok, cast(:d as jsonb), :e) returning id")
                .param("f", Timestamp.from(from)).param("t", Timestamp.from(now)).param("ok", ok).param("d", write(diffs)).param("e", error).query(Long.class).single();
        if (!ok && !alertEmail.isEmpty()) {
            email.sendNotice(alertEmail, "en", EmailSender.Notice.OPS_ALERT, Map.of("alert",
                    "Billing reconciliation: " + (error != null ? "failed (" + error + ")" : diffs.size() + " difference(s); see the console's Billing tab"),
                    "time", now.toString()));
        }
        return runs(1).stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    public List<Run> runs(int limit) {
        return jdbc.sql("select id, ran_at, window_from, window_to, ok, differences::text, error from sf_billing_reconciliations order by ran_at desc, id desc limit :l")
                .param("l", limit).query((rs, i) -> new Run(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant(),
                        rs.getBoolean(5), read(rs.getString(6)), rs.getString(7))).list();
    }

    private String write(List<Difference> diffs) {
        try {
            return json.writeValueAsString(diffs);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private List<Difference> read(String text) {
        try {
            return List.of(json.readValue(text, Difference[].class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
