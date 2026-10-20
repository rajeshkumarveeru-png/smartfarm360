package com.smartfarm360.platform.admin;

import com.smartfarm360.platform.auth.PasswordPolicy;
import com.smartfarm360.platform.user.SfUserAccount;
import com.smartfarm360.platform.user.SfUserAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Locale;

/**
 * Creates a platform administrator on start-up. Off by default; enable with APP_BOOTSTRAP_ADMIN_ENABLED=true and
 * provide APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD (there are no built-in credentials).
 * An existing account with that email is promoted to platform administrator; its password is not changed.
 */
@Configuration
public class BootstrapAdminInitializer {
    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);

    @Bean
    @ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
    ApplicationRunner bootstrapPlatformAdmin(SfUserAccountRepository users, PasswordEncoder passwordEncoder,
                                             PlatformTransactionManager transactionManager,
                                             @Value("${app.bootstrap-admin.email:}") String configuredEmail,
                                             @Value("${app.bootstrap-admin.password:}") String password) {
        String email = configuredEmail.trim().toLowerCase(Locale.ROOT);
        if (email.isBlank() || password.isBlank()) {
            throw new IllegalStateException("APP_BOOTSTRAP_ADMIN_EMAIL and APP_BOOTSTRAP_ADMIN_PASSWORD are required when the bootstrap admin is enabled");
        }
        PasswordPolicy.check(password, email);
        return args -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            SfUserAccount user = users.findByEmailIgnoreCase(email).orElseGet(() -> {
                SfUserAccount created = new SfUserAccount();
                created.setEmail(email);
                created.setDisplayName("Platform administrator");
                created.setPasswordHash(passwordEncoder.encode(password));
                created.setPasswordChangedAt(Instant.now());
                return created;
            });
            if (!user.isActive()) throw new IllegalStateException("The bootstrap admin account exists but is inactive");
            user.setPlatformRole(SfUserAccount.PLATFORM_ADMIN);
            users.save(user);
            log.info("Bootstrap platform administrator ensured for {}", email.charAt(0) + "***" + email.substring(email.indexOf('@')));
        });
    }
}
