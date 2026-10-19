package com.smartfarm360.config;

import com.smartfarm360.entity.SfTenant;
import com.smartfarm360.entity.SfTenantMembership;
import com.smartfarm360.entity.SfUserAccount;
import com.smartfarm360.repository.SfTenantMembershipRepository;
import com.smartfarm360.repository.SfTenantRepository;
import com.smartfarm360.repository.SfUserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Locale;

/**
 * Bootstraps a local development administrator if the account does not exist.
 *
 * Default credentials: username "admin", password "admin123".
 * Disable with APP_BOOTSTRAP_ADMIN_ENABLED=false outside local development.
 */
@Configuration
public class DataInitializer {

    @Bean
    @ConditionalOnProperty(
        name = "app.bootstrap-admin.enabled",
        havingValue = "true",
        matchIfMissing = true
    )
    ApplicationRunner initializeAdmin(
            SfUserAccountRepository users,
            SfTenantRepository tenants,
            SfTenantMembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager,
            @Value("${app.bootstrap-admin.username:admin}") String username,
            @Value("${app.bootstrap-admin.password:admin123}") String password,
            @Value("${app.bootstrap-admin.tenant-name:SmartFarm360 Admin}") String tenantName,
            @Value("${app.bootstrap-admin.tenant-slug:smartfarm360-admin}") String tenantSlug) {

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        return args -> transactions.executeWithoutResult(status -> initialize(
                users, tenants, memberships, passwordEncoder,
                username, password, tenantName, tenantSlug));
    }

    public void initialize(
            SfUserAccountRepository users,
            SfTenantRepository tenants,
            SfTenantMembershipRepository memberships,
            PasswordEncoder passwordEncoder,
            String configuredUsername,
            String configuredPassword,
            String tenantName,
            String tenantSlug) {

        String username = configuredUsername == null ? "admin" : configuredUsername.trim();
        if (username.isBlank()) {
            throw new IllegalStateException("Bootstrap admin username must not be blank");
        }
        if (configuredPassword == null || configuredPassword.isBlank()) {
            throw new IllegalStateException("Bootstrap admin password must not be blank");
        }

        SfUserAccount user = users.findByEmailIgnoreCase(username).orElseGet(() -> {
            SfUserAccount newUser = new SfUserAccount();
            newUser.setEmail(username.toLowerCase(Locale.ROOT));
            newUser.setDisplayName("Administrator");
            newUser.setPasswordHash(passwordEncoder.encode(configuredPassword));
            newUser.setActive(true);
            return users.save(newUser);
        });

        if (!user.isActive()) {
            throw new IllegalStateException(
                "The configured bootstrap admin account exists but is inactive. Activate it explicitly."
            );
        }

        SfTenant tenant = tenants.findBySlugIgnoreCase(tenantSlug).orElseGet(() -> {
            SfTenant newTenant = new SfTenant();
            newTenant.setName(tenantName);
            newTenant.setSlug(tenantSlug);
            newTenant.setPreferredLanguage("en");
            newTenant.setActive(true);
            return tenants.save(newTenant);
        });

        SfTenantMembership membership = memberships
                .findByTenantIdAndUserEmailIgnoreCase(tenant.getId(), user.getEmail())
                .orElseGet(() -> {
                    SfTenantMembership newMembership = new SfTenantMembership();
                    newMembership.setTenant(tenant);
                    newMembership.setUser(user);
                    newMembership.setRole("ADMIN");
                    newMembership.setActive(true);
                    return newMembership;
                });
        membership.setRole("ADMIN");
        membership.setActive(true);
        memberships.save(membership);
    }
}
