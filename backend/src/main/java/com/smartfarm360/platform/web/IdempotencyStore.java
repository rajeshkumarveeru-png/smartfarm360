package com.smartfarm360.platform.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The general idempotency store (mobile offline design, N4): one row per tenant and key, claimed before the request runs and
 * completed with its successful response, or released when the request fails so it can be sent again. Rows older than
 * 60 days are removed as new keys are claimed.
 */
@Component
public class IdempotencyStore {
    private final JdbcClient jdbc;

    public IdempotencyStore(JdbcClient jdbc) { this.jdbc = jdbc; }

    public record Stored(long id, long userId, String method, String path, String requestHash, Integer status, String contentType, String body) {}

    Optional<Stored> find(long tenantId, String key) {
        return jdbc.sql("""
                        select id, user_id, method, path, request_hash, response_status, response_type, response_body
                        from sf_idempotent_requests where tenant_id = :t and idempotency_key = :k""")
                .param("t", tenantId).param("k", key)
                .query((rs, i) -> new Stored(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), (Integer) rs.getObject(6),
                        rs.getString(7), rs.getString(8))).optional();
    }

    /** Claims a key for this request; empty when another request already holds it. Each statement commits on its own. */
    Optional<Long> claim(long tenantId, long userId, String key, String method, String path, String hash) {
        jdbc.sql("delete from sf_idempotent_requests where tenant_id = :t and created_at < now() - interval '60 days'").param("t", tenantId).update();
        return jdbc.sql("""
                        insert into sf_idempotent_requests (tenant_id, user_id, idempotency_key, method, path, request_hash)
                        values (:t, :u, :k, :m, :p, :h) on conflict (tenant_id, idempotency_key) do nothing returning id""")
                .param("t", tenantId).param("u", userId).param("k", key).param("m", method).param("p", path).param("h", hash)
                .query(Long.class).optional();
    }

    void complete(long id, int status, String contentType, String body) {
        jdbc.sql("update sf_idempotent_requests set response_status = :s, response_type = :ct, response_body = :b, completed_at = now() where id = :id")
                .param("s", status).param("ct", contentType).param("b", body).param("id", id).update();
    }

    void release(long id) {
        jdbc.sql("delete from sf_idempotent_requests where id = :id and response_status is null").param("id", id).update();
    }
}
