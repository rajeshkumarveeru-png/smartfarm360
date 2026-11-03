package com.smartfarm360.platform.mail;

import com.smartfarm360.platform.export.ReportLabels;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/** SMTP when {@code SPRING_MAIL_HOST} is set (production requires it), otherwise emails are only logged (O5). */
@Configuration
public class MailConfig {
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender emailSender(@Value("${spring.mail.host:}") String host, @Value("${app.mail.from}") String from,
                            @Value("${app.mail.dev-log-links:false}") boolean logLinks, ObjectProvider<JavaMailSender> mail, ReportLabels labels) {
        JavaMailSender sender = mail.getIfAvailable();
        if (host == null || host.isBlank() || sender == null) return new LoggingEmailSender(logLinks);
        return new SmtpEmailSender(sender, labels, from);
    }
}
