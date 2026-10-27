package com.smartfarm360.platform.ops;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.util.List;

/** Stops a production start with unsafe settings, with one line per problem (O2). */
@Component
@Profile("prod")
public class ProductionSettingsCheck {
    public ProductionSettingsCheck(Environment env, JdbcClient jdbc) {
        boolean superuser = jdbc.sql("select rolsuper from pg_roles where rolname = current_user").query(Boolean.class).single();
        List<String> problems = ProductionSettings.problems(env::getProperty, superuser);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production settings:\n - " + String.join("\n - ", problems));
        }
    }
}
