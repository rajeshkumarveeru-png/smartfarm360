package com.smartfarm360.platform.billing;

import com.fasterxml.jackson.databind.JsonNode;
import com.smartfarm360.finance.FinanceTestSupport;
import com.smartfarm360.platform.tenant.TenantDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Billing and payments design (Phase 15): trial, checkout, signed webhooks, GST invoices, renewals and grace, plan
 * changes, cancellation, refunds, the console, reconciliation, booking in the farm's books, and tenant isolation.
 * Gateway events are signed by the fake gateway's secret in Razorpay's format, so the real webhook path runs.
 */
class BillingTest extends FinanceTestSupport {
    @Autowired private PaymentGateway paymentGateway;
    @Autowired private SubscriptionService subscriptions;
    @Autowired private ReconciliationService reconciliation;
    @Autowired private InvoiceService invoiceService;
    @Autowired private TenantDataService tenantData;
    @Autowired private PlatformTransactionManager transactions;

    private FakePaymentGateway fake;
    private static final String TN_GSTIN = "33ABCDE1234F1Z7";
    private static final String KA_GSTIN = "29AAACR5055K1Z3";

    @BeforeEach
    void gateway() {
        fake = (FakePaymentGateway) paymentGateway;
        fake.down = false;
    }

    private String b(long tenantId, String path) { return "/api/v1/tenants/" + tenantId + "/billing" + path; }

    private static String fy() { return Gst.financialYear(LocalDate.now(SubscriptionService.INDIA)); }

    private void profile(User user, long tenantId, String state, String gstin) throws Exception {
        Map<String, Object> p = new HashMap<>(Map.of("legalName", "Murugan Farms", "addressLine1", "12 Canal Road", "city", "Erode", "pincode", "638001",
                "stateCode", state, "email", user.email()));
        if (gstin != null) p.put("gstin", gstin);
        put(b(tenantId, "/profile"), user, p).andExpect(status().isOk());
    }

    /** Starts a checkout and returns the fake gateway's subscription id. */
    private String checkout(User user, long tenantId, String plan, String period) throws Exception {
        JsonNode r = body(post(b(tenantId, "/checkout"), user, Map.of("planCode", plan, "period", period)).andExpect(status().isOk()));
        String url = r.get("checkoutUrl").asText();
        assertThat(url).contains("?fake=sub_fake");
        return url.substring(url.indexOf("?fake=") + 6);
    }

    private String webhook(String eventId, Object payload) throws Exception {
        String body = json.writeValueAsString(payload);
        MvcResult r = mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/webhooks/fake").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Razorpay-Event-Id", eventId).header("X-Razorpay-Signature", GatewayEvent.sign(fake.webhookSecret(), body))).andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        return json.readTree(r.getResponse().getContentAsString()).get("outcome").asText();
    }

    private static Map<String, Object> event(String type, Instant at, Map<String, Object> payload) {
        return Map.of("entity", "event", "event", type, "created_at", at.getEpochSecond(), "payload", payload);
    }

    private static Map<String, Object> subscription(String id, Instant start, Instant end) {
        Map<String, Object> e = new HashMap<>(Map.of("id", id));
        if (start != null) e.put("current_start", start.getEpochSecond());
        if (end != null) e.put("current_end", end.getEpochSecond());
        return Map.of("entity", e);
    }

    private String charged(String subId, String paymentId, String rupees, Instant at, Instant start, Instant end) throws Exception {
        long paise = new BigDecimal(rupees).movePointRight(2).longValueExact();
        return webhook("evt_" + paymentId, event("subscription.charged", at, Map.of("subscription", subscription(subId, start, end),
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", paise, "method", "upi", "subscription_id", subId)))));
    }

    private String failed(String subId, Instant at) throws Exception {
        return webhook("evt_fail_" + UUID.randomUUID(), event("subscription.pending", at, Map.of("subscription", subscription(subId, null, null))));
    }

    private JsonNode overview(User user, long tenantId) throws Exception { return body(get(b(tenantId, ""), user).andExpect(status().isOk())); }

    private String plan(long tenantId) { return jdbc.queryForObject("select plan_code from sf_tenants where id = ?", String.class, tenantId); }

    /** A farm business on a paid, active GROWER plan (Tamil Nadu buyer), paid for one period from {@code start}. */
    private String active(String period, Instant start, Instant end, String amount) throws Exception {
        newFinanceTenant("bill-" + UUID.randomUUID().toString().substring(0, 6));
        profile(owner, tenant, "33", TN_GSTIN);
        String sub = checkout(owner, tenant, "GROWER", period);
        assertThat(charged(sub, "pay_" + UUID.randomUUID(), amount, Instant.now(), start, end)).isEqualTo("APPLIED");
        return sub;
    }

    // ---- Trial (P3) and plans (P1, P2) -------------------------------------------------------------------------------

    @Test
    void aNewBusinessGetsOneTrialPerUserAndFallsBackToFreeWithNothingHidden() throws Exception {
        newFinanceTenant("trial-" + UUID.randomUUID().toString().substring(0, 6));
        JsonNode o = overview(owner, tenant);
        assertThat(o.get("state").asText()).isEqualTo("TRIAL");
        assertThat(o.get("effectivePlanCode").asText()).isEqualTo("GROWER");
        assertThat(Duration.between(Instant.now(), Instant.parse(o.get("trialEndsAt").asText())).toDays()).isBetween(29L, 30L);
        assertThat(plan(tenant)).isEqualTo("GROWER");
        // The plans an owner sees: FREE and the paid ones with prices before GST; not the early businesses' plan
        List<String> codes = new ArrayList<>();
        o.get("plans").forEach(p -> codes.add(p.get("code").asText()));
        assertThat(codes).containsExactly("FREE", "GROWER", "ESTATE");
        assertThat(o.at("/plans/1/prices/1/current/amount").decimalValue()).isEqualByComparingTo("4990.00");

        for (int i = 1; i <= 4; i++) newFarm(owner, tenant, "Field " + i);

        // A second business of the same user starts on FREE: 3 farms
        long second = newTenant(owner, "Second " + UUID.randomUUID().toString().substring(0, 4));
        assertThat(overview(owner, second).get("state").asText()).isEqualTo("NONE");
        assertThat(plan(second)).isEqualTo("FREE");
        for (int i = 1; i <= 3; i++) newFarm(owner, second, "S" + i);
        post("/api/v1/tenants/" + second + "/farms", owner, Map.of("name", "S4")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));

        // Reminders 7 days and 1 day before the end, then FREE; the four farms stay, the fifth is refused
        Instant end = Instant.parse(o.get("trialEndsAt").asText());
        subscriptions.runDaily(end.minus(Duration.ofDays(6)));
        subscriptions.runDaily(end.minus(Duration.ofDays(5)));
        subscriptions.runDaily(end.minus(Duration.ofHours(12)));
        assertThat(mail.to(owner.email()).stream().filter(s -> s.type().equals("TRIAL_ENDING")).count()).isEqualTo(2);
        // In its last week every member sees a banner
        jdbc.update("update sf_subscriptions set trial_ends_at = now() + interval '3 days' where tenant_id = ?", tenant);
        assertThat(overview(owner, tenant).get("banner").asText()).isEqualTo("TRIAL_ENDING");
        subscriptions.runDaily(Instant.now().plus(Duration.ofDays(3)).plusSeconds(60));
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("ENDED");
        assertThat(plan(tenant)).isEqualTo("FREE");
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("TRIAL_ENDED"))).isTrue();
        assertThat(body(get("/api/v1/tenants/" + tenant + "/farms", owner).andExpect(status().isOk())).size()).isGreaterThanOrEqualTo(4);
        post("/api/v1/tenants/" + tenant + "/farms", owner, Map.of("name", "Field 5")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PLAN_LIMIT_REACHED"));
    }

    // ---- Billing details and checkout (P4, P5) ------------------------------------------------------------------------

    @Test
    void onlyOwnersPayAndCheckoutNeedsValidBillingDetailsAndChangesNothingUntilTheGatewayConfirms() throws Exception {
        newFinanceTenant("checkout-" + UUID.randomUUID().toString().substring(0, 6));
        User accountant = newUser("acct");
        addMember(owner, tenant, accountant, "ACCOUNTANT");
        User worker = newUser("worker");
        addMember(owner, tenant, worker, "WORKER");

        post(b(tenant, "/checkout"), owner, Map.of("planCode", "GROWER", "period", "YEARLY")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BILLING_PROFILE_INCOMPLETE"));
        Map<String, Object> details = new HashMap<>(Map.of("legalName", "M", "addressLine1", "A", "city", "Erode", "pincode", "638001", "stateCode", "33",
                "email", owner.email()));
        details.put("gstin", "33ABCDE1234F1Z5");
        put(b(tenant, "/profile"), owner, details).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GSTIN"));
        details.put("gstin", KA_GSTIN);
        put(b(tenant, "/profile"), owner, details).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("GSTIN_STATE_MISMATCH"));
        details.put("stateCode", "25");
        details.remove("gstin");
        put(b(tenant, "/profile"), owner, details).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_STATE"));
        put(b(tenant, "/profile"), accountant, details).andExpect(status().isForbidden());
        profile(owner, tenant, "33", TN_GSTIN);
        assertThat(body(get(b(tenant, "/profile"), accountant).andExpect(status().isOk())).get("gstin").asText()).isEqualTo(TN_GSTIN);
        get(b(tenant, "/profile"), worker).andExpect(status().isForbidden());
        get(b(tenant, "/invoices"), worker).andExpect(status().isForbidden());
        assertThat(overview(worker, tenant).get("state").asText()).isEqualTo("TRIAL");   // every member sees the plan

        post(b(tenant, "/checkout"), accountant, Map.of("planCode", "GROWER", "period", "YEARLY")).andExpect(status().isForbidden());
        post(b(tenant, "/checkout"), owner, Map.of("planCode", "FREE", "period", "YEARLY")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_NOT_FOR_SALE"));
        String sub = checkout(owner, tenant, "GROWER", "YEARLY");
        // The gateway's plan carries the price with GST: 4,990 + 18%
        assertThat(jdbc.queryForObject("select count(*) from sf_gateway_plans where plan_code = 'GROWER' and period = 'YEARLY' and amount = 5888.20", Long.class)).isEqualTo(1);
        JsonNode o = overview(owner, tenant);
        assertThat(o.get("state").asText()).isEqualTo("TRIAL");
        assertThat(o.get("checkoutStarted").asBoolean()).isTrue();
        assertThat(sub).startsWith("sub_fake");

        fake.down = true;
        post(b(tenant, "/checkout"), owner, Map.of("planCode", "ESTATE", "period", "MONTHLY")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("GATEWAY_UNAVAILABLE"));
    }

    // ---- Webhooks and invoices (P4, P6) --------------------------------------------------------------------------------

    @Test
    void aSignedChargeActivatesThePlanAndIssuesAGstInvoiceOnceOnly() throws Exception {
        newFinanceTenant("invoice-" + UUID.randomUUID().toString().substring(0, 6));
        profile(owner, tenant, "33", TN_GSTIN);
        String sub = checkout(owner, tenant, "GROWER", "YEARLY");
        Instant now = Instant.now();
        Instant end = now.plus(Duration.ofDays(365));

        // A bad signature and an unknown subscription change nothing
        String body = json.writeValueAsString(event("subscription.activated", now, Map.of("subscription", subscription(sub, now, end))));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/billing/webhooks/fake").contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("X-Razorpay-Event-Id", "evt_bad").header("X-Razorpay-Signature", GatewayEvent.sign("wrong-secret", body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
        assertThat(webhook("evt_unknown_" + sub, event("subscription.activated", now, Map.of("subscription", subscription("sub_nobody", now, end)))))
                .isEqualTo("UNMATCHED");
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("TRIAL");

        String paymentId = "pay_" + UUID.randomUUID();
        assertThat(charged(sub, paymentId, "5888.20", now, now, end)).isEqualTo("APPLIED");
        JsonNode o = overview(owner, tenant);
        assertThat(o.get("state").asText()).isEqualTo("ACTIVE");
        assertThat(o.get("planCode").asText()).isEqualTo("GROWER");
        assertThat(o.get("period").asText()).isEqualTo("YEARLY");
        assertThat(o.get("checkoutStarted").asBoolean()).isFalse();
        assertThat(Instant.parse(o.get("paidUntil").asText()).getEpochSecond()).isEqualTo(end.getEpochSecond());

        JsonNode invoices = body(get(b(tenant, "/invoices"), owner).andExpect(status().isOk()));
        assertThat(invoices).hasSize(1);
        JsonNode inv = invoices.get(0);
        assertThat(inv.get("number").asText()).matches("SF/" + fy() + "/\\d{6}");
        money(inv.get("taxableAmount"), "4990.00");
        money(inv.get("cgst"), "449.10");
        money(inv.get("sgst"), "449.10");
        money(inv.get("igst"), "0.00");
        money(inv.get("total"), "5888.20");
        assertThat(inv.get("buyerGstin").asText()).isEqualTo(TN_GSTIN);
        assertThat(inv.get("sellerGstin").asText()).isEqualTo("33AABCS1234A1Z8");
        assertThat(inv.get("sac").asText()).isEqualTo("998314");
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("PAYMENT_RECEIVED"))).isTrue();

        MvcResult pdf = get(b(tenant, "/invoices/" + inv.get("id").asLong() + "/pdf"), owner).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andReturn();
        assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");

        // The same delivery again, and the same payment in another event: still one payment and one invoice
        assertThat(charged(sub, paymentId, "5888.20", now, now, end)).isEqualTo("DUPLICATE");
        assertThat(webhook("evt_other_" + paymentId, event("subscription.charged", now, Map.of("subscription", subscription(sub, now, end),
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 588820)))))).isEqualTo("IGNORED");
        assertThat(body(get(b(tenant, "/invoices"), owner)).size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sf_payments where customer_tenant_id = ?", Long.class, tenant)).isEqualTo(1);

        // An issued invoice cannot be changed or deleted, only booked
        long invoiceId = inv.get("id").asLong();
        assertThatThrownBy(() -> jdbc.update("update sf_invoices set total = 1 where id = ?", invoiceId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("delete from sf_invoices where id = ?", invoiceId)).hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("update sf_invoice_lines set description = 'x' where invoice_id = ?", invoiceId)).hasMessageContaining("immutable");
    }

    @Test
    void anotherStatePaysIgst() throws Exception {
        newFinanceTenant("igst-" + UUID.randomUUID().toString().substring(0, 6));
        profile(owner, tenant, "29", KA_GSTIN);
        String sub = checkout(owner, tenant, "GROWER", "MONTHLY");
        Instant now = Instant.now();
        charged(sub, "pay_" + UUID.randomUUID(), "588.82", now, now, now.plus(Duration.ofDays(30)));
        // The gateway charges the price with IGST: 499 + 18%
        assertThat(jdbc.queryForObject("select gateway_amount from sf_subscriptions where tenant_id = ?", BigDecimal.class, tenant)).isEqualByComparingTo("588.82");
        JsonNode inv = body(get(b(tenant, "/invoices"), owner)).get(0);
        money(inv.get("taxableAmount"), "499.00");
        money(inv.get("igst"), "89.82");
        money(inv.get("cgst"), "0.00");
        assertThat(inv.get("buyerStateCode").asText()).isEqualTo("29");
    }

    // ---- Renewals, failed payments and grace (P8, P9) ---------------------------------------------------------------

    @Test
    void aFailedRenewalGetsFourteenDaysOfGraceThenFreeLimitsUntilPaidAgain() throws Exception {
        Instant now = Instant.now();
        String sub = active("MONTHLY", now.minus(Duration.ofDays(29)), now.plus(Duration.ofDays(1)), "588.82");
        assertThat(failed(sub, Instant.now())).isEqualTo("APPLIED");
        JsonNode o = overview(owner, tenant);
        assertThat(o.get("state").asText()).isEqualTo("PAST_DUE");
        assertThat(o.get("banner").asText()).isEqualTo("PAST_DUE");
        assertThat(o.get("effectivePlanCode").asText()).isEqualTo("GROWER");   // grace keeps the plan
        Instant graceEnd = Instant.parse(o.get("graceEndsAt").asText());
        assertThat(Duration.between(now, graceEnd).toDays()).isBetween(13L, 14L);
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("RENEWAL_FAILED"))).isTrue();
        // A second failure while past due changes nothing, and an older event is ignored
        assertThat(failed(sub, Instant.now())).isEqualTo("IGNORED");
        assertThat(failed(sub, now.minus(Duration.ofDays(2)))).isEqualTo("IGNORED");

        subscriptions.runDaily(graceEnd.minus(Duration.ofDays(2)));
        assertThat(mail.to(owner.email()).stream().filter(s -> s.type().equals("GRACE_ENDING")).count()).isEqualTo(1);
        subscriptions.runDaily(graceEnd.minus(Duration.ofDays(1)));
        assertThat(mail.to(owner.email()).stream().filter(s -> s.type().equals("GRACE_ENDING")).count()).isEqualTo(1);

        for (int i = 1; i <= 4; i++) newFarm(owner, tenant, "Field " + i);
        subscriptions.runDaily(graceEnd.plusSeconds(60));
        o = overview(owner, tenant);
        assertThat(o.get("state").asText()).isEqualTo("LAPSED");
        assertThat(o.get("banner").asText()).isEqualTo("LAPSED");
        assertThat(plan(tenant)).isEqualTo("FREE");
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("PLAN_LAPSED"))).isTrue();
        // Nothing is locked: the books are read and written as before; only a fifth farm is refused
        get("/api/v1/tenants/" + tenant + "/farms", owner).andExpect(status().isOk());
        invoice("bills", "2026-10-01", null, cash, line("SEEDS", 100, null));
        post("/api/v1/tenants/" + tenant + "/farms", owner, Map.of("name", "Field 5")).andExpect(status().isConflict());

        // Paying later reactivates the plan, with a new invoice
        Instant paidAt = Instant.now();
        assertThat(charged(sub, "pay_" + UUID.randomUUID(), "588.82", paidAt, paidAt, paidAt.plus(Duration.ofDays(30)))).isEqualTo("APPLIED");
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(plan(tenant)).isEqualTo("GROWER");
        assertThat(body(get(b(tenant, "/invoices"), owner)).size()).isEqualTo(2);
        newFarm(owner, tenant, "Field 5");
    }

    // ---- Changing plans and cancelling (P10, P11) ---------------------------------------------------------------------

    @Test
    void anUpgradeChargesTheProRataDifferenceAndADowngradeWaitsForRenewal() throws Exception {
        Instant now = Instant.now();
        Instant paidUntil = now.plus(Duration.ofDays(15));
        String sub = active("MONTHLY", now.minus(Duration.ofDays(15)), paidUntil, "588.82");

        post(b(tenant, "/change"), owner, Map.of("planCode", "GROWER", "period", "MONTHLY")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PLAN_UNCHANGED"));
        JsonNode r = body(post(b(tenant, "/change"), owner, Map.of("planCode", "ESTATE", "period", "MONTHLY")).andExpect(status().isOk()));
        assertThat(r.get("checkoutUrl").asText()).contains("plink_fake");
        // (1,999 − 499) × 15 of 30 days = 750 + 18% GST
        FakePaymentGateway.Call link = fake.calls("LINK").get(fake.calls("LINK").size() - 1);
        assertThat(link.amount()).isEqualByComparingTo("885.00");
        assertThat(overview(owner, tenant).get("planCode").asText()).isEqualTo("GROWER");   // not before it is paid

        String paymentId = "pay_" + UUID.randomUUID();
        assertThat(webhook("evt_link_" + paymentId, event("payment_link.paid", Instant.now(), Map.of(
                "payment_link", Map.of("entity", Map.of("id", "plink", "reference_id", link.detail())),
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 88500, "method", "card")))))).isEqualTo("APPLIED");
        assertThat(overview(owner, tenant).get("planCode").asText()).isEqualTo("ESTATE");
        assertThat(plan(tenant)).isEqualTo("ESTATE");
        JsonNode upgrade = body(get(b(tenant, "/invoices"), owner)).get(0);
        money(upgrade.get("total"), "885.00");
        money(upgrade.get("taxableAmount"), "750.00");
        assertThat(fake.calls("CHANGE").stream().anyMatch(c -> c.id().equals(sub))).isTrue();

        // A downgrade waits for the renewal; the gateway is told to charge the lower plan then
        int changes = fake.calls("CHANGE").size();
        JsonNode down = body(post(b(tenant, "/change"), owner, Map.of("planCode", "GROWER", "period", "MONTHLY")).andExpect(status().isOk()));
        assertThat(down.get("checkoutUrl").isNull()).isTrue();
        assertThat(down.at("/overview/pendingPlanCode").asText()).isEqualTo("GROWER");
        assertThat(down.at("/overview/planCode").asText()).isEqualTo("ESTATE");
        assertThat(fake.calls("CHANGE")).hasSize(changes + 1);
        assertThat(charged(sub, "pay_" + UUID.randomUUID(), "588.82", Instant.now(), paidUntil, paidUntil.plus(Duration.ofDays(30)))).isEqualTo("APPLIED");
        JsonNode o = overview(owner, tenant);
        assertThat(o.get("planCode").asText()).isEqualTo("GROWER");
        assertThat(o.get("pendingPlanCode").isNull()).isTrue();
        assertThat(body(get(b(tenant, "/invoices"), owner)).get(0).get("planCode").asText()).isEqualTo("GROWER");
    }

    @Test
    void cancellingKeepsThePlanToTheEndOfThePaidPeriodAndDeletionEndsItAtOnce() throws Exception {
        Instant now = Instant.now();
        Instant paidUntil = now.plus(Duration.ofDays(10));
        String sub = active("MONTHLY", now.minus(Duration.ofDays(20)), paidUntil, "588.82");
        JsonNode o = body(post(b(tenant, "/cancel"), owner, Map.of()).andExpect(status().isOk()));
        assertThat(o.get("state").asText()).isEqualTo("CANCELLED");
        assertThat(o.get("effectivePlanCode").asText()).isEqualTo("GROWER");
        assertThat(fake.calls("CANCEL_AT_END").stream().anyMatch(c -> c.id().equals(sub))).isTrue();
        post(b(tenant, "/cancel"), owner, Map.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_ACTIVE"));
        post(b(tenant, "/checkout"), owner, Map.of("planCode", "GROWER", "period", "MONTHLY")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_EXISTS"));
        subscriptions.runDaily(paidUntil.plusSeconds(60));
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("ENDED");
        assertThat(plan(tenant)).isEqualTo("FREE");

        // Subscribing again replaces the earlier gateway subscription, which is stopped so it can never charge again
        String resub = checkout(owner, tenant, "GROWER", "MONTHLY");
        Instant at = Instant.now();
        assertThat(charged(resub, "pay_" + UUID.randomUUID(), "588.82", at, at, at.plus(Duration.ofDays(30)))).isEqualTo("APPLIED");
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("ACTIVE");
        assertThat(fake.calls("CANCEL_NOW").stream().anyMatch(c -> c.id().equals(sub))).isTrue();
        assertThat(fake.calls("CANCEL_NOW").stream().noneMatch(c -> c.id().equals(resub))).isTrue();
        // A late charge on the old subscription matches nothing and changes nothing
        assertThat(charged(sub, "pay_" + UUID.randomUUID(), "588.82", Instant.now(), at, at.plus(Duration.ofDays(30)))).isEqualTo("UNMATCHED");

        // Another business on a paid plan asks for deletion: the subscription ends at once and is not restored
        String sub2 = active("YEARLY", now, now.plus(Duration.ofDays(365)), "5888.20");
        String name = jdbc.queryForObject("select name from sf_tenants where id = ?", String.class, tenant);
        post("/api/v1/tenants/" + tenant + "/deletion", owner, Map.of("confirmName", name)).andExpect(status().isOk());
        assertThat(fake.calls("CANCEL_NOW").stream().anyMatch(c -> c.id().equals(sub2))).isTrue();
        delete("/api/v1/tenants/" + tenant + "/deletion", owner).andExpect(status().isOk());
        assertThat(overview(owner, tenant).get("state").asText()).isEqualTo("ENDED");
        assertThat(plan(tenant)).isEqualTo("FREE");

        // The final deletion removes the subscription but keeps the invoices and payments (8 years, P6)
        post("/api/v1/tenants/" + tenant + "/deletion", owner, Map.of("confirmName", name)).andExpect(status().isOk());
        long deleted = tenant;
        tenantData.runDueDeletions(Instant.now().plus(Duration.ofDays(31)));
        assertThat(jdbc.queryForObject("select count(*) from sf_tenants where id = ?", Long.class, deleted)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_subscriptions where tenant_id = ?", Long.class, deleted)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_billing_profiles where tenant_id = ?", Long.class, deleted)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from sf_invoices where customer_tenant_id = ?", Long.class, deleted)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from sf_payments where customer_tenant_id = ?", Long.class, deleted)).isEqualTo(1);
        // The owner does not get a second trial
        long again = newTenant(owner, "Again " + UUID.randomUUID().toString().substring(0, 4));
        assertThat(plan(again)).isEqualTo("FREE");
    }

    // ---- The platform console (P10, P12) ------------------------------------------------------------------------------

    @Test
    void platformAdministratorsExtendCompRefundAndSetPricesWithAReason() throws Exception {
        Instant now = Instant.now();
        active("YEARLY", now, now.plus(Duration.ofDays(365)), "5888.20");
        User admin = platformAdmin("billing-admin");
        get("/api/v1/platform/billing", owner).andExpect(status().isForbidden());
        JsonNode summary = body(get("/api/v1/platform/billing", admin).andExpect(status().isOk()));
        assertThat(summary.at("/subscriptions/ACTIVE").asLong()).isPositive();

        // A partial refund: a credit note for exactly that amount, through the gateway
        JsonNode payments = body(get("/api/v1/platform/billing/payments?tenantId=" + tenant, admin).andExpect(status().isOk()));
        long paymentId = payments.get(0).get("id").asLong();
        post("/api/v1/platform/billing/payments/" + paymentId + "/refund", admin, Map.of("amount", "1000.00")).andExpect(status().isBadRequest());
        post("/api/v1/platform/billing/payments/" + paymentId + "/refund", admin, Map.of("amount", "9000.00", "reason", "Too much"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFUND_TOO_LARGE"));
        JsonNode note = body(post("/api/v1/platform/billing/payments/" + paymentId + "/refund", admin, Map.of("amount", "2944.10", "reason", "Half year unused"))
                .andExpect(status().isOk()));
        assertThat(note.get("number").asText()).matches("SFCN/" + fy() + "/\\d{6}");
        money(note.get("total"), "2944.10");
        money(note.get("cgst"), "224.55");
        assertThat(fake.calls("REFUND").stream().anyMatch(c -> c.amount().compareTo(new BigDecimal("2944.10")) == 0)).isTrue();
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("REFUND_ISSUED"))).isTrue();
        JsonNode inv = body(get(b(tenant, "/invoices"), owner)).get(0);
        assertThat(inv.get("creditNotes")).hasSize(1);
        money(inv.get("refundedAmount"), "2944.10");
        get(b(tenant, "/invoices/" + inv.get("id").asLong() + "/credit-notes/" + note.get("id").asLong() + "/pdf"), owner).andExpect(status().isOk());
        post("/api/v1/platform/billing/payments/" + paymentId + "/refund", admin, Map.of("amount", "2944.11", "reason", "More"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REFUND_TOO_LARGE"));

        // A comp gives a plan free until a date and ends on its own
        long other = newTenant(newUser("comp-owner"), "Pilot " + UUID.randomUUID().toString().substring(0, 4));
        jdbc.update("update sf_subscriptions set state = 'ENDED' where tenant_id = ?", other);
        put("/api/v1/platform/billing/tenants/" + other + "/comp", admin, Map.of("planCode", "ESTATE", "until", LocalDate.now().plusDays(10).toString(), "reason", "Pilot farm"))
                .andExpect(status().isNoContent());
        assertThat(plan(other)).isEqualTo("ESTATE");
        subscriptions.runDaily(Instant.now().plus(Duration.ofDays(12)));
        assertThat(plan(other)).isEqualTo("FREE");

        // Trial and grace extensions need the right state and a reason
        User trialOwner = newUser("trial-ext");
        long trial = newTenant(trialOwner, "Trial " + UUID.randomUUID().toString().substring(0, 4));
        post("/api/v1/platform/billing/tenants/" + trial + "/trial", admin, Map.of("days", 10)).andExpect(status().isBadRequest());
        post("/api/v1/platform/billing/tenants/" + trial + "/grace", admin, Map.of("days", 10, "reason", "x")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_PAST_DUE"));
        Instant before = Instant.parse(overview(trialOwner, trial).get("trialEndsAt").asText());
        post("/api/v1/platform/billing/tenants/" + trial + "/trial", admin, Map.of("days", 10, "reason", "Harvest season")).andExpect(status().isNoContent());
        assertThat(Duration.between(before, Instant.parse(overview(trialOwner, trial).get("trialEndsAt").asText())).toDays()).isEqualTo(10);

        // A new price needs 30 days' notice; subscribers are told, and renewals from that date charge it
        post("/api/v1/platform/billing/prices", admin, Map.of("planCode", "GROWER", "period", "YEARLY", "amount", "5490.00",
                "validFrom", LocalDate.now().plusDays(10).toString())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRICE_NOTICE_TOO_SHORT"));
        LocalDate from = LocalDate.now(SubscriptionService.INDIA).plusDays(40 + new Random().nextInt(2000));
        post("/api/v1/platform/billing/prices", admin, Map.of("planCode", "GROWER", "period", "YEARLY", "amount", "5490.00", "validFrom", from.toString()))
                .andExpect(status().isCreated());
        assertThat(mail.to(owner.email()).stream().anyMatch(s -> s.type().equals("PRICE_CHANGE"))).isTrue();
        assertThat(body(get("/api/v1/platform/audit", admin)).toString()).contains("BILLING_PRICE_SET").contains("BILLING_REFUNDED").contains("BILLING_COMP_GIVEN");
    }

    @Test
    void theReconciliationListsWhatDiffersAndAFailedEventCanBeReplayed() throws Exception {
        Instant now = Instant.now();
        String sub = active("MONTHLY", now.minus(Duration.ofDays(15)), now.plus(Duration.ofDays(15)), "588.82");
        User admin = platformAdmin("recon-admin");
        fake.states.put(sub, "ACTIVE");
        fake.captured.add(new PaymentGateway.GatewayPayment("pay_missing_" + sub, sub, null, new BigDecimal("588.82"), now.minusSeconds(60)));
        JsonNode run = body(post("/api/v1/platform/billing/reconciliation", admin, Map.of()).andExpect(status().isOk()));
        assertThat(run.get("ok").asBoolean()).isFalse();
        assertThat(run.get("differences").toString()).contains("PAYMENT_NOT_RECORDED").contains("pay_missing_" + sub);
        assertThat(run.get("differences").toString()).doesNotContain("\"gatewayId\":\"" + sub + "\"");
        fake.captured.clear();

        // The gateway is down while an upgrade's payment arrives: the event is kept as FAILED and replayed later
        String link = body(post(b(tenant, "/change"), owner, Map.of("planCode", "ESTATE", "period", "MONTHLY")).andExpect(status().isOk()))
                .get("checkoutUrl").asText();
        assertThat(link).contains("plink_fake");
        String reference = fake.calls("LINK").get(fake.calls("LINK").size() - 1).detail();
        fake.down = true;
        String paymentId = "pay_" + UUID.randomUUID();
        assertThat(webhook("evt_" + paymentId, event("payment_link.paid", Instant.now(), Map.of("payment_link", Map.of("entity", Map.of("reference_id", reference)),
                "payment", Map.of("entity", Map.of("id", paymentId, "amount", 88500)))))).isEqualTo("FAILED");
        assertThat(plan(tenant)).isEqualTo("GROWER");
        assertThat(jdbc.queryForObject("select count(*) from sf_invoices where customer_tenant_id = ?", Long.class, tenant)).isEqualTo(1);
        fake.down = false;
        long eventId = jdbc.queryForObject("select id from sf_gateway_events where event_id = ?", Long.class, "evt_" + paymentId);
        JsonNode summary = body(get("/api/v1/platform/billing", admin));
        assertThat(summary.get("unappliedEvents").toString()).contains("evt_" + paymentId);
        assertThat(body(post("/api/v1/platform/billing/events/" + eventId + "/replay", admin, Map.of()).andExpect(status().isOk())).get("outcome").asText())
                .isEqualTo("APPLIED");
        assertThat(plan(tenant)).isEqualTo("ESTATE");
        post("/api/v1/platform/billing/events/" + eventId + "/replay", admin, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_APPLIED"));
    }

    // ---- The farm's books (P13) ------------------------------------------------------------------------------------------

    @Test
    void anOwnerCanRecordAPaidInvoiceInTheBooksOnce() throws Exception {
        Instant now = Instant.now();
        active("YEARLY", now, now.plus(Duration.ofDays(365)), "5888.20");
        JsonNode inv = body(get(b(tenant, "/invoices"), owner)).get(0);
        long invoiceId = inv.get("id").asLong();
        User worker = newUser("book-worker");
        addMember(owner, tenant, worker, "WORKER");
        post(f("/subscription-invoices/" + invoiceId + "/book"), worker, Map.of()).andExpect(status().isForbidden());

        JsonNode bill = body(post(f("/subscription-invoices/" + invoiceId + "/book"), owner, Map.of("financialAccountId", cash)).andExpect(status().isCreated()));
        money(bill.at("/summary/totalAmount"), "5888.20");
        assertThat(bill.at("/summary/type").asText()).isEqualTo("BILL");
        assertThat(bill.at("/summary/partyName").asText()).isEqualTo("SmartFarm360");
        assertThat(bill.at("/summary/paymentStatus").asText()).isEqualTo("PAID");
        assertThat(bill.at("/lines/0/categoryCode").asText()).isEqualTo("SOFTWARE");
        assertThat(bill.get("referenceNo").asText()).isEqualTo(inv.get("number").asText());
        assertThat(body(get(b(tenant, "/invoices"), owner)).get(0).get("bookedDocumentId").asLong()).isEqualTo(bill.at("/summary/id").asLong());
        post(f("/subscription-invoices/" + invoiceId + "/book"), owner, Map.of()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVOICE_ALREADY_BOOKED"));
        assertBooksBalance("2099-12-31");
    }

    // ---- Tenant isolation (ADR 0011) ------------------------------------------------------------------------------------

    @Test
    void oneBusinessNeverSeesAnotherBusinessesBilling() throws Exception {
        Instant now = Instant.now();
        active("YEARLY", now, now.plus(Duration.ofDays(365)), "5888.20");
        User ownerA = owner;
        long tenantA = tenant;
        long invoiceA = body(get(b(tenantA, "/invoices"), ownerA)).get(0).get("id").asLong();
        newFinanceTenant("other-" + UUID.randomUUID().toString().substring(0, 6));

        // A non-member is told the farm business does not exist, as everywhere else
        for (String path : List.of("", "/profile", "/invoices", "/invoices/" + invoiceA + "/pdf")) {
            get(b(tenantA, path), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TENANT_NOT_FOUND"));
        }
        post(b(tenantA, "/cancel"), owner, Map.of()).andExpect(status().isNotFound());
        put(b(tenantA, "/profile"), owner, Map.of("legalName", "x", "addressLine1", "x", "city", "x", "pincode", "600001", "stateCode", "33", "email", owner.email()))
                .andExpect(status().isNotFound());
        get(b(tenant, "/invoices/" + invoiceA + "/pdf"), owner).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVOICE_NOT_FOUND"));
        assertThat(body(get(b(tenant, "/invoices"), owner))).isEmpty();
        post(f("/subscription-invoices/" + invoiceA + "/book"), owner, Map.of()).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select booked_document_id from sf_invoices where id = ?", Long.class, invoiceA)).isNull();

        // The database refuses it too: in B's scope A's invoices and subscription do not exist
        Long seen = new TransactionTemplate(transactions).execute(s -> {
            jdbc.queryForList("select set_config('app.tenant_id', ?, true)", String.valueOf(tenant));
            return jdbc.queryForObject("select count(*) from sf_invoices where customer_tenant_id = ?", Long.class, tenantA)
                    + jdbc.queryForObject("select count(*) from sf_subscriptions where tenant_id = ?", Long.class, tenantA)
                    + jdbc.queryForObject("select count(*) from sf_payments where customer_tenant_id = ?", Long.class, tenantA);
        });
        assertThat(seen).isZero();
    }

    // ---- Numbering under load (P6, P15) ------------------------------------------------------------------------------

    @Test
    void invoiceNumbersStayGaplessUnderConcurrentPaymentsAndAFailedIssueTakesNoNumber() throws Exception {
        newFinanceTenant("numbers-" + UUID.randomUUID().toString().substring(0, 6));
        profile(owner, tenant, "33", TN_GSTIN);
        LocalDate day = LocalDate.of(2031, 6, 1);   // its own financial year, so other tests do not share the series
        int n = 8;
        List<Long> payments = new ArrayList<>();
        for (int i = 0; i < n + 1; i++) {
            payments.add(jdbc.queryForObject("""
                    insert into sf_payments (customer_tenant_id, gateway, gateway_payment_id, kind, amount, captured_at)
                    values (?, 'fake', ?, 'RENEWAL', 588.82, now()) returning id""", Long.class, tenant, "pay_n_" + UUID.randomUUID()));
        }
        TransactionTemplate tx = new TransactionTemplate(transactions);
        // A failed transaction after taking a number leaves no gap
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            invoiceService.issue(tenant, payments.get(n), day, new InvoiceService.Line("x", "GROWER", "MONTHLY", day, day.plusDays(29)));
            throw new IllegalStateException("rolled back");
        })).hasMessage("rolled back");
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> numbers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long payment = payments.get(i);
            numbers.add(pool.submit(() -> {
                start.await();
                return tx.execute(s -> invoiceService.issue(tenant, payment, day, new InvoiceService.Line("x", "GROWER", "MONTHLY", day, day.plusDays(29))).number());
            }));
        }
        start.countDown();
        Set<String> got = new TreeSet<>();
        for (Future<String> f : numbers) got.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        List<String> expected = new ArrayList<>();
        long first = jdbc.queryForObject("select min(cast(split_part(number, '/', 3) as bigint)) from sf_invoices where financial_year = '2031-32' and number like 'SF/%'", Long.class);
        for (int i = 0; i < n; i++) expected.add(Gst.number("SF", "2031-32", first + i));
        assertThat(got).containsExactlyElementsOf(expected);
        assertThat(first).isEqualTo(1);
    }
}
