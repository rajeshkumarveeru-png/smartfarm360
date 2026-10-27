package com.smartfarm360.platform.ops;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The prod profile refuses each unsafe setting with a message naming what to fix (SaaS operations design, O2). */
class ProductionSettingsTest {
    private static Map<String, String> safe() {
        Map<String, String> p = new HashMap<>();
        p.put("app.auth.jwt-secret", "a-production-secret-of-more-than-32-bytes");
        p.put("app.auth.refresh-cookie-secure", "true");
        p.put("app.mail.dev-log-links", "false");
        p.put("springdoc.api-docs.enabled", "false");
        p.put("springdoc.swagger-ui.enabled", "false");
        p.put("app.auth.public-web-url", "https://app.smartfarm360.example");
        p.put("spring.mail.host", "smtp.example");
        p.put("app.ops.alert-email", "ops@example.com");
        p.put("app.mfa.encryption-key", Base64.getEncoder().encodeToString(new byte[32]));
        p.put("app.bootstrap-admin.enabled", "false");
        p.put("app.billing.gateway", "razorpay");
        p.put("app.billing.razorpay.key-id", "rzp_live_0123456789");
        p.put("app.billing.razorpay.key-secret", "a-live-key-secret");
        p.put("app.billing.webhook-secret", "a-webhook-secret-of-16+");
        p.put("app.billing.seller.gstin", "33AABCS1234A1Z8");
        p.put("app.billing.seller.name", "SmartFarm360 Private Limited");
        p.put("app.billing.seller.address", "1 Main Road, Coimbatore 641001");
        return p;
    }

    private static List<String> problems(Map<String, String> p, boolean superuser) { return ProductionSettings.problems(p::get, superuser); }

    @Test
    void aSafeConfigurationStarts() {
        assertThat(problems(safe(), false)).isEmpty();
    }

    @Test
    void eachUnsafeSettingIsNamed() {
        record Case(String key, String value, String message) {}
        List<Case> cases = List.of(
                new Case("app.auth.jwt-secret", "short", "APP_JWT_SECRET"),
                new Case("app.auth.jwt-secret", null, "APP_JWT_SECRET"),
                new Case("app.auth.refresh-cookie-secure", "false", "APP_REFRESH_COOKIE_SECURE"),
                new Case("app.mail.dev-log-links", "true", "APP_MAIL_DEV_LOG_LINKS"),
                new Case("springdoc.api-docs.enabled", "true", "APP_API_DOCS_ENABLED"),
                new Case("springdoc.swagger-ui.enabled", "true", "APP_API_DOCS_ENABLED"),
                new Case("app.auth.public-web-url", "http://app.example", "APP_PUBLIC_WEB_URL"),
                new Case("spring.mail.host", "", "SPRING_MAIL_HOST"),
                new Case("app.ops.alert-email", "", "APP_OPS_ALERT_EMAIL"),
                new Case("app.mfa.encryption-key", "not-base64!", "APP_MFA_ENCRYPTION_KEY"),
                new Case("app.mfa.encryption-key", Base64.getEncoder().encodeToString(new byte[16]), "APP_MFA_ENCRYPTION_KEY"),
                new Case("app.bootstrap-admin.enabled", "true", "APP_BOOTSTRAP_ADMIN_ENABLED"),
                new Case("app.billing.gateway", "fake", "APP_BILLING_GATEWAY"),
                new Case("app.billing.razorpay.key-secret", "", "APP_BILLING_RAZORPAY_KEY_SECRET"),
                new Case("app.billing.razorpay.key-id", "rzp_test_0123456789", "live key"),
                new Case("app.billing.webhook-secret", "short", "APP_BILLING_WEBHOOK_SECRET"),
                new Case("app.billing.seller.gstin", "33AABCS1234A1Z9", "APP_BILLING_SELLER_GSTIN"),
                new Case("app.billing.seller.address", "", "APP_BILLING_SELLER_ADDRESS"));
        for (Case c : cases) {
            Map<String, String> p = safe();
            p.put(c.key(), c.value());
            assertThat(problems(p, false)).as(c.key() + "=" + c.value()).hasSize(1).first().asString().contains(c.message());
        }
        assertThat(problems(safe(), true)).singleElement().asString().contains("superuser").contains("Row-Level Security");
    }

    @Test
    void stagingTakesOnlyTestPaymentsOnAStagingAddress() {
        Map<String, String> p = safe();
        p.put("app.environment", "staging");
        p.put("app.auth.public-web-url", "https://staging.smartfarm360.example");
        p.put("app.billing.razorpay.key-id", "rzp_test_0123456789");
        assertThat(problems(p, false)).isEmpty();

        Map<String, String> live = new HashMap<>(p);
        live.put("app.billing.razorpay.key-id", "rzp_live_0123456789");
        assertThat(problems(live, false)).singleElement().asString().contains("test key").contains("staging");

        Map<String, String> productionAddress = new HashMap<>(p);
        productionAddress.put("app.auth.public-web-url", "https://app.smartfarm360.example");
        assertThat(problems(productionAddress, false)).singleElement().asString().contains("APP_PUBLIC_WEB_URL").contains("staging");

        Map<String, String> other = new HashMap<>(p);
        other.put("app.environment", "demo");
        assertThat(problems(other, false)).singleElement().asString().contains("APP_ENVIRONMENT");

        Map<String, String> production = new HashMap<>(p);
        production.put("app.environment", "production");
        assertThat(problems(production, false)).singleElement().asString().contains("live key");
    }
}
