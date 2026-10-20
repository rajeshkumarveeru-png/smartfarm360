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
 */
public abstract class EmbeddedPostgresSupport {
    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
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
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("app.bootstrap-admin.enabled", () -> "false");
        registry.add("app.auth.jwt-secret", () -> "test-only-signing-secret-at-least-32-bytes!!");
    }

    /** Creates a separate, empty database on the same server (for migration scenarios that must not share state). */
    protected static DataSource newDatabase(String name) throws SQLException {
        try (Connection c = POSTGRES.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("create database " + name);
        }
        return POSTGRES.getDatabase("postgres", name);
    }
}
