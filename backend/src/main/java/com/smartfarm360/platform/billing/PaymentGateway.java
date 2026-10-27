package com.smartfarm360.platform.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The payment gateway (billing design, section 5, P4). Payment happens on the gateway's hosted pages: SmartFarm360
 * keeps the gateway's ids, never card, UPI or bank details. {@link RazorpayGateway} talks to Razorpay;
 * {@link FakePaymentGateway} is for development and tests. Amounts are in rupees, GST included.
 */
public interface PaymentGateway {
    String name();

    /** The gateway's plan for a recurring amount; created once per (plan, period, amount) and remembered by the caller. */
    String createPlan(String planCode, String period, BigDecimal amount, String description);

    /** A new recurring subscription, paid on the gateway's checkout page at {@code checkoutUrl}. */
    Checkout createSubscription(String gatewayPlanId, String period, Map<String, String> notes);

    /** A one-off payment (the pro-rata part of an upgrade), paid on the gateway's page. {@code reference} comes back in the webhook. */
    Checkout createPaymentLink(BigDecimal amount, String description, String reference, String customerEmail);

    /** The next renewals charge the new plan; the current period is not changed. */
    void changePlanAtCycleEnd(String gatewaySubscriptionId, String gatewayPlanId);

    /** Stops renewals; {@code atCycleEnd} false stops it at once. */
    void cancel(String gatewaySubscriptionId, boolean atCycleEnd);

    /** Refunds part or all of a captured payment; returns the gateway's refund id. */
    String refund(String gatewayPaymentId, BigDecimal amount);

    /** The captured payments in a window, for the nightly reconciliation (P12). */
    List<GatewayPayment> payments(Instant from, Instant to);

    /** The gateway's view of one subscription's state, in our words, or empty if it does not know it. */
    Optional<String> subscriptionState(String gatewaySubscriptionId);

    /** Checks a webhook's signature over its exact body before anything in it is read. */
    boolean validSignature(String body, String signature);

    record Checkout(String gatewayId, String url) {}

    record GatewayPayment(String paymentId, String subscriptionId, String reference, BigDecimal amount, Instant capturedAt) {}
}
