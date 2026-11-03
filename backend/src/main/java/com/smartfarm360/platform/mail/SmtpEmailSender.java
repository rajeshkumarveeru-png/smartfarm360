package com.smartfarm360.platform.mail;

import com.smartfarm360.platform.export.ReportLabels;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends email through any SMTP provider (SaaS operations design, O5). Texts come from the catalogs shared with the
 * web and phone apps ({@code email.*}), in the recipient's language. A failed send is retried three times within
 * 15 minutes, then given up and counted ({@code sf.mail.failures}). Addresses are masked in the log; links never appear.
 */
public class SmtpEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);
    static final List<Duration> RETRIES = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(9));
    private final JavaMailSender mail;
    private final ReportLabels labels;
    private final String from;
    private final ScheduledExecutorService retries = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sf-mail-retry");
        t.setDaemon(true);
        return t;
    });

    public SmtpEmailSender(JavaMailSender mail, ReportLabels labels, String from) {
        this.mail = mail;
        this.labels = labels;
        this.from = from;
    }

    @Override
    public void sendPasswordReset(String to, String resetUrl, String language) { send(to, language, "passwordReset", Map.of("url", resetUrl)); }

    @Override
    public void sendRegistrationAttempt(String to, String language) { send(to, language, "registrationAttempt", Map.of()); }

    @Override
    public void sendInvitation(String to, String tenantName, String acceptUrl, String language) {
        send(to, language, "invitation", Map.of("tenant", tenantName, "url", acceptUrl));
    }

    @Override
    public void sendNotice(String to, String language, Notice notice, Map<String, String> params) { send(to, language, notice.key, params); }

    /** The message as it is sent; package-visible for template tests. */
    SimpleMailMessage message(String to, String language, String key, Map<String, String> params) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(to);
        m.setSubject(labels.t(language, "email." + key + ".subject", params));
        m.setText(labels.t(language, "email." + key + ".body", params) + "\n\n" + labels.t(language, "email.footer", params));
        return m;
    }

    private void send(String to, String language, String key, Map<String, String> params) {
        attempt(message(to, language, key, params), key, 0);
    }

    private void attempt(SimpleMailMessage m, String key, int tried) {
        String to = LoggingEmailSender.mask(m.getTo() == null || m.getTo().length == 0 ? null : m.getTo()[0]);
        try {
            mail.send(m);
            log.info("Email {} sent to {}", key, to);
        } catch (RuntimeException e) {
            if (tried < RETRIES.size()) {
                log.warn("Email {} to {} failed ({}); retrying in {}", key, to, e.getClass().getSimpleName(), RETRIES.get(tried));
                retries.schedule(() -> attempt(m, key, tried + 1), RETRIES.get(tried).toMillis(), TimeUnit.MILLISECONDS);
            } else {
                log.error("Email {} to {} failed after {} retries; giving up", key, to, RETRIES.size());
                Metrics.counter("sf.mail.failures").increment();
            }
        }
    }
}
