package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Chooses the payment gateway: {@code razorpay}, or {@code fake} (the default, for development and tests). */
@Configuration
public class GatewayConfig {
    @Bean
    PaymentGateway paymentGateway(BillingProperties properties, ObjectMapper json, @Value("${app.auth.public-web-url}") String publicWebUrl) {
        return switch (properties.gateway) {
            case "razorpay" -> new RazorpayGateway(json, properties);
            case "fake" -> new FakePaymentGateway(properties, publicWebUrl);
            default -> throw new IllegalStateException("APP_BILLING_GATEWAY must be razorpay or fake");
        };
    }
}
