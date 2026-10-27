package com.smartfarm360.platform.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The platform audit log (O6): every platform administrator action and every platform job (exports, deletions), kept
 * 2 years. It has no tenant key, so it survives a farm business's deletion. No secrets or farm data in the details.
 */
@Component
public class PlatformAudit {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    public PlatformAudit(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(Long actorUserId, String action, String targetType, Long targetId, Map<String, ?> details) {
        String text;
        try {
            text = json.writeValueAsString(details == null ? Map.of() : details);
        } catch (JsonProcessingException e) {
            text = "{}";
        }
        jdbc.sql("insert into sf_platform_audit (actor_user_id, action, target_type, target_id, details) values (:a, :act, :tt, :ti, cast(:d as jsonb))")
                .param("a", actorUserId).param("act", action).param("tt", targetType).param("ti", targetId).param("d", text).update();
    }

    /** Removes entries older than 2 years (run with the daily jobs). */
    public int prune() {
        return jdbc.sql("delete from sf_platform_audit where occurred_at < now() - interval '2 years'").update();
    }
}
