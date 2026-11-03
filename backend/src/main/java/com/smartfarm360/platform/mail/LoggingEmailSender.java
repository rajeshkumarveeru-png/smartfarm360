package com.smartfarm360.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Used when no SMTP provider is configured (local development): records that an email would have been sent, with the
 * recipient masked. Links (which carry one-time tokens) are only logged when {@code app.mail.dev-log-links=true},
 * which production refuses (ProductionSettings).
 */
public class LoggingEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);
    private final boolean logLinks;

    public LoggingEmailSender(boolean logLinks) {
        this.logLinks = logLinks;
        if (logLinks) log.warn("app.mail.dev-log-links=true: one-time links will be written to the log. Never enable this in production.");
    }

    @Override
    public void sendPasswordReset(String to, String resetUrl, String language) {
        log.info("Email not sent (no provider configured): password reset to {}", mask(to));
        if (logLinks) log.warn("DEV ONLY password reset link for {}: {}", mask(to), resetUrl);
    }

    @Override
    public void sendRegistrationAttempt(String to, String language) {
        log.info("Email not sent (no provider configured): registration attempt notice to {}", mask(to));
    }

    @Override
    public void sendInvitation(String to, String tenantName, String acceptUrl, String language) {
        log.info("Email not sent (no provider configured): invitation to {}", mask(to));
        if (logLinks) log.warn("DEV ONLY invitation link for {}: {}", mask(to), acceptUrl);
    }

    @Override
    public void sendNotice(String to, String language, Notice notice, Map<String, String> params) {
        log.info("Email not sent (no provider configured): {} to {}", notice, mask(to));
        if (logLinks && params.containsKey("url")) log.warn("DEV ONLY {} link for {}: {}", notice, mask(to), params.get("url"));
    }

    public static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
