package com.smartfarm360.platform.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** What the clients need to know about the signed-in account besides its profile (O6, O12). */
@Component
public class AccountFacts {
    private final JdbcClient jdbc;

    public AccountFacts(JdbcClient jdbc) { this.jdbc = jdbc; }

    public boolean twoStepEnabled(long userId) {
        return jdbc.sql("select exists (select 1 from sf_user_mfa where user_id = :u and enabled_at is not null)").param("u", userId).query(Boolean.class).single();
    }

    /**
     * The latest published terms and privacy notice the user has not accepted yet, as {@code KIND:version}. Nothing is
     * pending until the product owner publishes a document.
     */
    public List<String> pendingConsents(long userId) {
        List<String> out = new ArrayList<>();
        for (String kind : List.of("TERMS", "PRIVACY")) {
            int latest = jdbc.sql("select coalesce(max(version), 0) from sf_legal_documents where kind = :k").param("k", kind).query(Integer.class).single();
            if (latest == 0) continue;
            boolean accepted = jdbc.sql("select exists (select 1 from sf_consents where user_id = :u and kind = :k and version >= :v and accepted)")
                    .param("u", userId).param("k", kind).param("v", latest).query(Boolean.class).single();
            if (!accepted) out.add(kind + ":" + latest);
        }
        return out;
    }
}
