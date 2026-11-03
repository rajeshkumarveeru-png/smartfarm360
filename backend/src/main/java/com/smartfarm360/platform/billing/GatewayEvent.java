package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A webhook event in our words (billing design, section 5). Parsed from Razorpay's format, which the fake gateway
 * uses too, so tests exercise the real parsing.
 *
 * @param subscriptionId the gateway's subscription id, when the event is about one
 * @param reference      our reference of a one-off payment (an upgrade)
 * @param periodEnd      the end of the period the subscription is paid for, when the event gives it
 */
public record GatewayEvent(String eventId, String type, Kind kind, Instant createdAt, String subscriptionId, String paymentId,
                           String reference, BigDecimal amount, String method, Instant periodStart, Instant periodEnd) {

    public enum Kind {
        /** The mandate is authorised and the subscription is running. */
        ACTIVATED,
        /** A payment was captured for the subscription. */
        CHARGED,
        /** A renewal failed; the gateway retries. */
        PAYMENT_FAILED,
        /** The gateway stopped retrying. */
        HALTED,
        /** The subscription ended at the gateway (cancelled or all charges done). */
        CANCELLED,
        /** A one-off payment link was paid (the pro-rata part of an upgrade). */
        LINK_PAID,
        /** Recorded, nothing to do. */
        OTHER
    }

    public static GatewayEvent parse(ObjectMapper json, String eventId, String body) {
        try {
            JsonNode root = json.readTree(body);
            String type = root.path("event").asText("");
            JsonNode sub = root.path("payload").path("subscription").path("entity");
            JsonNode payment = root.path("payload").path("payment").path("entity");
            JsonNode link = root.path("payload").path("payment_link").path("entity");
            Kind kind = switch (type) {
                case "subscription.activated" -> Kind.ACTIVATED;
                case "subscription.charged" -> Kind.CHARGED;
                case "subscription.pending" -> Kind.PAYMENT_FAILED;
                case "payment.failed" -> payment.hasNonNull("subscription_id") || sub.hasNonNull("id") ? Kind.PAYMENT_FAILED : Kind.OTHER;
                case "subscription.halted" -> Kind.HALTED;
                case "subscription.cancelled", "subscription.completed" -> Kind.CANCELLED;
                case "payment_link.paid" -> Kind.LINK_PAID;
                default -> Kind.OTHER;
            };
            String subscriptionId = sub.hasNonNull("id") ? sub.get("id").asText() : text(payment, "subscription_id");
            String reference = text(link, "reference_id");
            BigDecimal amount = payment.hasNonNull("amount") ? BigDecimal.valueOf(payment.get("amount").asLong()).movePointLeft(2) : null;
            Instant created = root.hasNonNull("created_at") ? Instant.ofEpochSecond(root.get("created_at").asLong()) : Instant.now();
            String id = eventId == null || eventId.isBlank() ? sha256(body) : eventId;
            return new GatewayEvent(id, type, kind, created, subscriptionId, text(payment, "id"), reference, amount, text(payment, "method"),
                    epoch(sub, "current_start"), epoch(sub, "current_end"));
        } catch (Exception e) {
            throw new IllegalArgumentException("Unreadable webhook body", e);
        }
    }

    /** Razorpay's signature: hex HMAC-SHA256 of the exact body with the webhook secret. */
    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(String secret, String body, String signature) {
        if (secret == null || secret.isBlank() || signature == null || body == null) return false;
        return MessageDigest.isEqual(sign(secret, body).getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String text(JsonNode n, String field) { return n.hasNonNull(field) ? n.get(field).asText() : null; }

    private static Instant epoch(JsonNode n, String field) {
        return n.hasNonNull(field) && n.get(field).asLong() > 0 ? Instant.ofEpochSecond(n.get(field).asLong()) : null;
    }

    private static String sha256(String body) {
        try {
            return "body-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))).substring(0, 40);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
