package com.smartfarm360.platform.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MailConfig {
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    EmailSender emailSender(@Value("${app.mail.dev-log-links:false}") boolean logLinks) {
        return new LoggingEmailSender(logLinks);
    }
}
