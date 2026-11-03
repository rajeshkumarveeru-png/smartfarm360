package com.smartfarm360.platform.ops;

import com.smartfarm360.platform.billing.Gst;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;

/**
 * The settings production refuses to start without (SaaS operations design, section 3, O2). Pure, so each rule is
 * tested on its own; {@link ProductionSettingsCheck} applies it at start-up.
 */
public final class ProductionSettings {
    private ProductionSettings() {}

    /** Each problem found, as a message that names the environment variable to fix. Empty when production is safe. */
    public static List<String> problems(Function<String, String> property, boolean databaseSuperuser) {
        List<String> out = new ArrayList<>();
        String secret = value(property, "app.auth.jwt-secret");
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) out.add("APP_JWT_SECRET must be set (at least 32 bytes) in the prod profile");
        if (!"true".equalsIgnoreCase(value(property, "app.auth.refresh-cookie-secure"))) out.add("APP_REFRESH_COOKIE_SECURE must be true in the prod profile");
        if ("true".equalsIgnoreCase(value(property, "app.mail.dev-log-links"))) out.add("APP_MAIL_DEV_LOG_LINKS must be false in the prod profile");
        if ("true".equalsIgnoreCase(value(property, "springdoc.api-docs.enabled")) || "true".equalsIgnoreCase(value(property, "springdoc.swagger-ui.enabled"))) {
            out.add("APP_API_DOCS_ENABLED must be false in the prod profile");
        }
        if (!value(property, "app.auth.public-web-url").startsWith("https://")) out.add("APP_PUBLIC_WEB_URL must be an https:// address in the prod profile");
        if (value(property, "spring.mail.host").isBlank()) out.add("SPRING_MAIL_HOST must be set: production sends email (O5)");
        if (value(property, "app.ops.alert-email").isBlank()) out.add("APP_OPS_ALERT_EMAIL must be set: operational alerts need an address (O4)");
        if (!validKey(value(property, "app.mfa.encryption-key"))) out.add("APP_MFA_ENCRYPTION_KEY must be 32 random bytes, base64-encoded");
        if ("true".equalsIgnoreCase(value(property, "app.bootstrap-admin.enabled"))) out.add("APP_BOOTSTRAP_ADMIN_ENABLED must be false once the first administrator exists");
        String gateway = value(property, "app.billing.gateway");
        if (!"razorpay".equalsIgnoreCase(gateway)) out.add("APP_BILLING_GATEWAY must be razorpay in the prod profile (the fake gateway moves no money)");
        if (value(property, "app.billing.razorpay.key-id").isBlank() || value(property, "app.billing.razorpay.key-secret").isBlank()) {
            out.add("APP_BILLING_RAZORPAY_KEY_ID and APP_BILLING_RAZORPAY_KEY_SECRET must be set (billing design, P4)");
        }
        String environment = value(property, "app.environment");
        boolean testKey = value(property, "app.billing.razorpay.key-id").startsWith("rzp_test_");
        if (environment.isEmpty() || "production".equalsIgnoreCase(environment)) {
            if (testKey) out.add("APP_BILLING_RAZORPAY_KEY_ID must be a live key in the prod profile, not a test key");
        } else if ("staging".equalsIgnoreCase(environment)) {
            // Staging takes test payments only, and must not pass for production by its address
            if (!testKey) out.add("APP_BILLING_RAZORPAY_KEY_ID must be a Razorpay test key (rzp_test_) when APP_ENVIRONMENT is staging");
            if (!host(value(property, "app.auth.public-web-url")).contains("staging")) {
                out.add("APP_PUBLIC_WEB_URL must be a staging address (its host name contains \"staging\") when APP_ENVIRONMENT is staging");
            }
        } else {
            out.add("APP_ENVIRONMENT must be production or staging");
        }
        if (value(property, "app.billing.webhook-secret").length() < 16) out.add("APP_BILLING_WEBHOOK_SECRET must be set (at least 16 characters)");
        if (!Gst.validGstin(value(property, "app.billing.seller.gstin").toUpperCase())) out.add("APP_BILLING_SELLER_GSTIN must be the platform entity's valid GSTIN (billing design, P6)");
        if (value(property, "app.billing.seller.name").isBlank() || value(property, "app.billing.seller.address").isBlank()) {
            out.add("APP_BILLING_SELLER_NAME and APP_BILLING_SELLER_ADDRESS must be set: they are printed on every invoice");
        }
        if (databaseSuperuser) out.add("The database user must not be a superuser: superusers bypass Row-Level Security (ADR 0011)");
        return out;
    }

    static boolean validKey(String base64) {
        try {
            return !base64.isBlank() && Base64.getDecoder().decode(base64.trim()).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String host(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String value(Function<String, String> property, String key) {
        String v = property.apply(key);
        return v == null ? "" : v.trim();
    }
}
