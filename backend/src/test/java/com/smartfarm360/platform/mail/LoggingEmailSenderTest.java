package com.smartfarm360.platform.mail;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingEmailSenderTest {
    @Test
    void masksRecipients() {
        assertThat(LoggingEmailSender.mask("farmer@example.com")).isEqualTo("f***@example.com");
        assertThat(LoggingEmailSender.mask("not-an-email")).isEqualTo("***");
        assertThat(LoggingEmailSender.mask(null)).isEqualTo("***");
    }
}
