package com.smartfarm360.platform.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A gateway that moves no money, for development and tests (billing design, P15). It remembers what it was asked,
 * and signs webhooks with the configured secret in Razorpay's format, so tests drive the real webhook path.
 * The {@code prod} profile refuses it.
 */
public class FakePaymentGateway implements PaymentGateway {
    public record Call(String action, String id, BigDecimal amount, String detail) {}

    private final AtomicLong ids = new AtomicLong(1000);
    private final String webhookSecret;
    private final String checkoutBase;
    public final List<Call> calls = new CopyOnWriteArrayList<>();
    /** Captured payments the reconciliation sees; tests add to it. */
    public final List<GatewayPayment> captured = new CopyOnWriteArrayList<>();
    /** The gateway's state of each subscription; tests change it. */
    public final Map<String, String> states = new ConcurrentHashMap<>();
    /** When set, every call fails as an unreachable gateway would. */
    public volatile boolean down;

    public FakePaymentGateway(BillingProperties properties, String publicWebUrl) {
        this.webhookSecret = properties.webhookSecret.isBlank() ? "fake-webhook-secret" : properties.webhookSecret;
        this.checkoutBase = publicWebUrl;
    }

    public String webhookSecret() { return webhookSecret; }

    @Override public String name() { return "fake"; }

    @Override
    public String createPlan(String planCode, String period, BigDecimal amount, String description) {
        check();
        String id = "plan_fake" + ids.incrementAndGet();
        calls.add(new Call("PLAN", id, amount, planCode + "/" + period));
        return id;
    }

    @Override
    public Checkout createSubscription(String gatewayPlanId, String period, Map<String, String> notes) {
        check();
        String id = "sub_fake" + ids.incrementAndGet();
        calls.add(new Call("SUBSCRIPTION", id, null, gatewayPlanId));
        states.put(id, "CREATED");
        return new Checkout(id, checkoutBase + "/billing/return?fake=" + id);
    }

    @Override
    public Checkout createPaymentLink(BigDecimal amount, String description, String reference, String customerEmail) {
        check();
        String id = "plink_fake" + ids.incrementAndGet();
        calls.add(new Call("LINK", id, amount, reference));
        return new Checkout(id, checkoutBase + "/billing/return?fake=" + id);
    }

    @Override
    public void changePlanAtCycleEnd(String gatewaySubscriptionId, String gatewayPlanId) {
        check();
        calls.add(new Call("CHANGE", gatewaySubscriptionId, null, gatewayPlanId));
    }

    @Override
    public void cancel(String gatewaySubscriptionId, boolean atCycleEnd) {
        check();
        calls.add(new Call(atCycleEnd ? "CANCEL_AT_END" : "CANCEL_NOW", gatewaySubscriptionId, null, null));
        states.put(gatewaySubscriptionId, "CANCELLED");
    }

    @Override
    public String refund(String gatewayPaymentId, BigDecimal amount) {
        check();
        String id = "rfnd_fake" + ids.incrementAndGet();
        calls.add(new Call("REFUND", gatewayPaymentId, amount, id));
        return id;
    }

    @Override
    public List<GatewayPayment> payments(Instant from, Instant to) {
        check();
        return new ArrayList<>(captured.stream().filter(p -> !p.capturedAt().isBefore(from) && p.capturedAt().isBefore(to)).toList());
    }

    @Override
    public Optional<String> subscriptionState(String gatewaySubscriptionId) {
        check();
        return Optional.ofNullable(states.get(gatewaySubscriptionId));
    }

    @Override
    public boolean validSignature(String body, String signature) { return GatewayEvent.verify(webhookSecret, body, signature); }

    public List<Call> calls(String action) { return calls.stream().filter(c -> c.action().equals(action)).toList(); }

    private void check() {
        if (down) throw new com.smartfarm360.platform.web.ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE",
                "The payment gateway could not be reached. Try again later.");
    }
}
