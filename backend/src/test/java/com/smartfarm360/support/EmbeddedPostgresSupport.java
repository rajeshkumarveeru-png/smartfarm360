package com.smartfarm360.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Starts one real, empty PostgreSQL server per test JVM (no Docker required).
 * Integration tests extend this class so Liquibase runs against a clean database.
 * <p>
 * The application connects as {@value #APP_ROLE}, an ordinary role that owns its database, as in production. A
 * superuser would bypass Row-Level Security (ADR 0011), so every API test also runs against the policies.
 */
public abstract class EmbeddedPostgresSupport {
    public static final String APP_ROLE = "sf_app";
    private static final String APP_DATABASE = "smartfarm";
    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                s.execute("create role " + APP_ROLE + " login password '" + APP_ROLE + "' nosuperuser");
                s.execute("create database " + APP_DATABASE + " owner " + APP_ROLE);
            } catch (SQLException e) {
                throw new IllegalStateException("Could not create the application role", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { postgres.close(); } catch (IOException ignored) { }
            }));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl(APP_ROLE, APP_DATABASE));
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_ROLE);
        registry.add("app.bootstrap-admin.enabled", () -> "false");
        registry.add("app.auth.jwt-secret", () -> "test-only-signing-secret-at-least-32-bytes!!");
        // The platform entity printed on test invoices (Tamil Nadu) and the fake gateway's webhook secret (billing design)
        registry.add("app.billing.seller.name", () -> "SmartFarm360 Test Private Limited");
        registry.add("app.billing.seller.gstin", () -> "33AABCS1234A1Z8");
        registry.add("app.billing.seller.address", () -> "1 Test Street, Coimbatore, Tamil Nadu 641001");
        registry.add("app.billing.webhook-secret", () -> "test-webhook-secret-0123456789");
    }

    /** Creates a separate, empty database on the same server (for migration scenarios that must not share state). */
    protected static DataSource newDatabase(String name) throws SQLException {
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        return POSTGRES.getDatabase("postgres", name);
    }
}
