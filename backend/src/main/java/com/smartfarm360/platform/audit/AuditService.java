package com.smartfarm360.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.web.CorrelationIdFilter;
import com.smartfarm360.platform.web.PageResponse;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Append-only audit trail. Records are written in the caller's transaction, so they roll back with the change. */
@Service
public class AuditService {
    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditService(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** @param tenantId null for platform-level events (for example a password reset). Never pass secrets in {@code details}. */
    @Transactional
    public void record(Long tenantId, Long actorUserId, String action, String entityType, Object entityId, Map<String, ?> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Audit details are not serializable", e);
        }
        jdbc.sql("""
                        insert into sf_audit_history (tenant_id, actor_user_id, action, entity_type, entity_id, details, correlation_id)
                        values (:tenantId, :actor, :action, :entityType, :entityId, cast(:details as jsonb), :correlationId)""")
                .param("tenantId", tenantId)
                .param("actor", actorUserId)
                .param("action", action)
                .param("entityType", entityType)
                .param("entityId", entityId == null ? null : String.valueOf(entityId))
                .param("details", json)
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .update();
    }

    public record AuditEntry(long id, Long actorUserId, String actorEmail, String action, String entityType, String entityId,
                             Map<String, Object> details, Instant occurredAt) {}

    @Transactional(readOnly = true)
    public PageResponse<AuditEntry> list(long tenantId, int page, int size) {
        long total = jdbc.sql("select count(*) from sf_audit_history where tenant_id = :t").param("t", tenantId)
                .query(Long.class).single();
        List<AuditEntry> items = jdbc.sql("""
                        select a.id, a.actor_user_id, case when u.anonymised_at is not null then u.display_name else u.email end, a.action, a.entity_type, a.entity_id, a.details::text, a.occurred_at
                        from sf_audit_history a left join sf_users u on u.id = a.actor_user_id
                        where a.tenant_id = :t order by a.occurred_at desc, a.id desc limit :limit offset :offset""")
                .param("t", tenantId).param("limit", size).param("offset", (long) page * size)
                .query((rs, i) -> new AuditEntry(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), parse(rs.getString(7)), rs.getTimestamp(8).toInstant()))
                .list();
        return PageResponse.of(items, page, size, total);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }
}
