package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfarm360.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Razorpay Subscriptions, Payment Links and Refunds over its REST API (billing design, P4). Selected with
 * {@code APP_BILLING_GATEWAY=razorpay}. The key and secrets come from environment variables; nothing here is logged
 * beyond the gateway's ids and status codes.
 */
public class RazorpayGateway implements PaymentGateway {
    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);
    private static final String API = "https://api.razorpay.com/v1";
    /** Renewals before the subscription completes: 10 years either way. */
    private static final Map<String, Integer> TOTAL_COUNT = Map.of("MONTHLY", 120, "YEARLY", 10);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String auth;
    private final String webhookSecret;

    public RazorpayGateway(ObjectMapper json, BillingProperties properties) {
        this.json = json;
        this.auth = "Basic " + Base64.getEncoder().encodeToString((properties.razorpayKeyId + ":" + properties.razorpayKeySecret).getBytes(StandardCharsets.UTF_8));
        this.webhookSecret = properties.webhookSecret;
    }

    @Override public String name() { return "razorpay"; }

    @Override
    public String createPlan(String planCode, String period, BigDecimal amount, String description) {
        ObjectNode body = json.createObjectNode();
        body.put("period", "MONTHLY".equals(period) ? "monthly" : "yearly");
        body.put("interval", 1);
        ObjectNode item = body.putObject("item");
        item.put("name", description);
        item.put("amount", paise(amount));
        item.put("currency", "INR");
        body.putObject("notes").put("plan_code", planCode);
        return call("POST", "/plans", body).path("id").asText();
    }

    @Override
    public Checkout createSubscription(String gatewayPlanId, String period, Map<String, String> notes) {
        ObjectNode body = json.createObjectNode();
        body.put("plan_id", gatewayPlanId);
        body.put("total_count", TOTAL_COUNT.get(period));
        body.put("customer_notify", 1);
        ObjectNode n = body.putObject("notes");
        notes.forEach(n::put);
        JsonNode r = call("POST", "/subscriptions", body);
        return new Checkout(r.path("id").asText(), r.path("short_url").asText());
    }

    @Override
    public Checkout createPaymentLink(BigDecimal amount, String description, String reference, String customerEmail) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", paise(amount));
        body.put("currency", "INR");
        body.put("description", description);
        body.put("reference_id", reference);
        body.putObject("customer").put("email", customerEmail);
        body.putObject("notify").put("email", true);
        JsonNode r = call("POST", "/payment_links", body);
        return new Checkout(r.path("id").asText(), r.path("short_url").asText());
    }

    @Override
    public void changePlanAtCycleEnd(String gatewaySubscriptionId, String gatewayPlanId) {
        ObjectNode body = json.createObjectNode();
        body.put("plan_id", gatewayPlanId);
        body.put("schedule_change_at", "cycle_end");
        call("PATCH", "/subscriptions/" + gatewaySubscriptionId, body);
    }

    @Override
    public void cancel(String gatewaySubscriptionId, boolean atCycleEnd) {
        ObjectNode body = json.createObjectNode();
        body.put("cancel_at_cycle_end", atCycleEnd ? 1 : 0);
        call("POST", "/subscriptions/" + gatewaySubscriptionId + "/cancel", body);
    }

    @Override
    public String refund(String gatewayPaymentId, BigDecimal amount) {
        ObjectNode body = json.createObjectNode();
        body.put("amount", paise(amount));
        return call("POST", "/payments/" + gatewayPaymentId + "/refund", body).path("id").asText();
    }

    @Override
    public List<GatewayPayment> payments(Instant from, Instant to) {
        List<GatewayPayment> out = new ArrayList<>();
        for (int skip = 0; ; skip += 100) {
            JsonNode r = call("GET", "/payments?count=100&skip=" + skip + "&from=" + from.getEpochSecond() + "&to=" + to.getEpochSecond(), null);
            JsonNode items = r.path("items");
            for (JsonNode p : items) {
                if (!"captured".equals(p.path("status").asText()) && !"refunded".equals(p.path("status").asText())) continue;
                String sub = p.hasNonNull("subscription_id") ? p.get("subscription_id").asText() : null;
                String reference = p.path("notes").hasNonNull("reference_id") ? p.path("notes").get("reference_id").asText() : null;
                out.add(new GatewayPayment(p.path("id").asText(), sub, reference, BigDecimal.valueOf(p.path("amount").asLong()).movePointLeft(2),
                        Instant.ofEpochSecond(p.path("created_at").asLong())));
            }
            if (items.size() < 100) return out;
        }
    }

    @Override
    public Optional<String> subscriptionState(String gatewaySubscriptionId) {
        try {
            String status = call("GET", "/subscriptions/" + gatewaySubscriptionId, null).path("status").asText();
            return Optional.of(switch (status) {
                case "active", "authenticated" -> "ACTIVE";
                case "pending", "halted" -> "PAST_DUE";
                case "cancelled", "completed", "expired" -> "CANCELLED";
                default -> status.toUpperCase();
            });
        } catch (ApiException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean validSignature(String body, String signature) { return GatewayEvent.verify(webhookSecret, body, signature); }

    private JsonNode call(String method, String path, JsonNode body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", auth).header("Content-Type", "application/json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                log.warn("Razorpay {} {} answered {}", method, path.replaceAll("\\?.*", ""), r.statusCode());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "The payment gateway did not accept the request. Try again later.");
            }
            return json.readTree(r.body());
        } catch (ApiException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "The payment gateway could not be reached. Try again later.");
        } catch (Exception e) {
            log.warn("Razorpay {} {} failed: {}", method, path.replaceAll("\\?.*", ""), e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "The payment gateway could not be reached. Try again later.");
        }
    }

    private static long paise(BigDecimal rupees) { return rupees.movePointRight(2).longValueExact(); }
}
