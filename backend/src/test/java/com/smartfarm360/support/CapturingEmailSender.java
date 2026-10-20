package com.smartfarm360.support;

import com.smartfarm360.platform.mail.EmailSender;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records emails instead of sending them, so tests can follow reset and invitation links. */
public class CapturingEmailSender implements EmailSender {
    public record Sent(String type, String to, String url) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override public void sendPasswordReset(String to, String resetUrl, String language) { sent.add(new Sent("RESET", to, resetUrl)); }
    @Override public void sendRegistrationAttempt(String to, String language) { sent.add(new Sent("REGISTRATION_ATTEMPT", to, null)); }
    @Override public void sendInvitation(String to, String tenantName, String acceptUrl, String language) { sent.add(new Sent("INVITATION", to, acceptUrl)); }

    public List<Sent> to(String email) { return sent.stream().filter(s -> s.to().equalsIgnoreCase(email)).toList(); }

    /** The one-time token from the most recent link of {@code type} sent to {@code email}. */
    public String lastToken(String email, String type) {
        List<Sent> matching = to(email).stream().filter(s -> s.type().equals(type)).toList();
        if (matching.isEmpty()) throw new AssertionError("No " + type + " email sent to " + email);
        String url = matching.get(matching.size() - 1).url();
        return url.substring(url.indexOf("#token=") + "#token=".length());
    }
}
