package com.smartfarm360.platform.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.export.ReportLabels;
import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Every email has its subject and text in English and Tamil, with its parameters filled (SaaS operations design, O5). */
class EmailTemplatesTest {
    private final ReportLabels labels = new ReportLabels(new ObjectMapper());

    @Test
    void everyEmailIsWrittenInBothLanguagesWithItsLinksAndNames() {
        List<String> keys = new ArrayList<>(List.of("passwordReset", "registrationAttempt", "invitation"));
        for (EmailSender.Notice n : EmailSender.Notice.values()) keys.add(n.key);
        Map<String, String> params = Map.of("url", "https://app.example/x#token=abc", "tenant", "Lakshmi Farms", "date", "2026-12-22",
                "until", "2026-10-23T10:00:00Z", "time", "2026-10-22T06:10:00Z", "alert", "Site down");
        SmtpEmailSender sender = new SmtpEmailSender(new JavaMailSenderImpl(), labels, "SmartFarm360 <no-reply@example.com>");
        for (String key : keys) {
            SimpleMailMessage en = sender.message("a@example.com", "en", key, params);
            SimpleMailMessage ta = sender.message("a@example.com", "ta", key, params);
            for (SimpleMailMessage m : List.of(en, ta)) {
                assertThat(m.getSubject()).as(key).doesNotStartWith("email.").doesNotContain("{{");
                assertThat(m.getText()).as(key).doesNotContain("email.").doesNotContain("{{");
                assertThat(m.getFrom()).isEqualTo("SmartFarm360 <no-reply@example.com>");
            }
            assertThat(ta.getText()).as(key + " is translated").isNotEqualTo(en.getText());
        }
        assertThat(sender.message("a@example.com", "ta", "invitation", params).getText()).contains("Lakshmi Farms").contains("#token=abc");
        assertThat(sender.message("a@example.com", "en", "deletionScheduled", params).getSubject()).contains("2026-12-22");
    }

    @Test
    void addressesAreMaskedInTheLog() {
        assertThat(LoggingEmailSender.mask("lakshmi@example.com")).isEqualTo("l***@example.com");
        assertThat(LoggingEmailSender.mask(null)).isEqualTo("***");
    }
}
