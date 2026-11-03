package com.smartfarm360.platform.mail;

import java.util.Map;

/**
 * Outgoing emails. Links contain one-time secrets: implementations must never log them in production.
 * {@link SmtpEmailSender} sends through the configured SMTP provider (O5); {@link LoggingEmailSender} only records that
 * an email would have been sent (local development).
 */
public interface EmailSender {
    void sendPasswordReset(String to, String resetUrl, String language);

    /** Sent when someone tries to register an email that already has an account (registration does not reveal this). */
    void sendRegistrationAttempt(String to, String language);

    void sendInvitation(String to, String tenantName, String acceptUrl, String language);

    /** The other account and operations emails (SaaS operations design, section 10). {@code params} fill the template. */
    void sendNotice(String to, String language, Notice notice, Map<String, String> params);

    /** Each notice has a subject and body in both catalogs: {@code email.<key>.subject} and {@code email.<key>.body}. */
    enum Notice {
        VERIFY_EMAIL("verifyEmail"),
        EXPORT_READY("exportReady"),
        DELETION_SCHEDULED("deletionScheduled"),
        DELETION_CANCELLED("deletionCancelled"),
        DELETION_DONE("deletionDone"),
        SUPPORT_ACCESS_GRANTED("supportAccessGranted"),
        ADMIN_SIGN_IN("adminSignIn"),
        ACCOUNT_DELETED("accountDeleted"),
        OPS_ALERT("opsAlert"),
        TRIAL_ENDING("trialEnding"),
        TRIAL_ENDED("trialEnded"),
        PAYMENT_RECEIVED("paymentReceived"),
        RENEWAL_FAILED("renewalFailed"),
        GRACE_ENDING("graceEnding"),
        PLAN_LAPSED("planLapsed"),
        PRICE_CHANGE("priceChange"),
        REFUND_ISSUED("refundIssued");

        public final String key;

        Notice(String key) { this.key = key; }
    }
}
