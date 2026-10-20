package com.smartfarm360.platform.mail;

/**
 * Outgoing account emails. Links contain one-time secrets: implementations must never log them in production.
 * The only implementation today is {@link LoggingEmailSender}; a real provider will be added once one is chosen.
 */
public interface EmailSender {
    void sendPasswordReset(String to, String resetUrl, String language);

    /** Sent when someone tries to register an email that already has an account (registration does not reveal this). */
    void sendRegistrationAttempt(String to, String language);

    void sendInvitation(String to, String tenantName, String acceptUrl, String language);
}
