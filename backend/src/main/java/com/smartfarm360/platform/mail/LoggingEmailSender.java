package com.smartfarm360.platform.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder used until an email provider is selected: records that an email would have been sent, with the
 * recipient masked. Links (which carry one-time tokens) are only logged when {@code app.mail.dev-log-links=true},
 * which is for local development only.
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

    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }
}
