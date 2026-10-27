package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.mail.AfterCommit;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Subscriptions and their states (billing design, sections 4, 7 and 8):
 * {@code TRIAL → ACTIVE ⇄ PAST_DUE → LAPSED}, plus {@code CANCELLED} (until the paid period ends) and {@code ENDED}.
 * <ul>
 *   <li>A farm business's plan ({@code sf_tenants.plan_code}, read by {@code PlanLimits}) is always the subscription's
 *   effective plan: a comp, else the subscribed plan while it is paid, in trial or in grace, else the plan it had
 *   before (FREE, or FREE_LEGACY for early businesses).</li>
 *   <li>Never holds the books hostage (section 2): falling back only lowers the limits for new additions.</li>
 *   <li>Only signed gateway events activate or renew a plan; the browser's return page never does.</li>
 * </ul>
 */
@Service
public class SubscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final Duration GRACE_REMINDER = Duration.ofDays(3);

    private final JdbcClient jdbc;
    private final PaymentGateway gateway;
    private final PlanCatalog catalog;
    private final InvoiceService invoices;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final EmailSender email;
    private final BillingProperties properties;
    private final Clock clock;
    private final String webUrl;

    public SubscriptionService(JdbcClient jdbc, PaymentGateway gateway, PlanCatalog catalog, InvoiceService invoices, AuditService audit,
                               PlatformAudit platformAudit, EmailSender email, BillingProperties properties, Clock clock,
                               @Value("${app.auth.public-web-url}") String publicWebUrl) {
        this.jdbc = jdbc;
        this.gateway = gateway;
        this.catalog = catalog;
        this.invoices = invoices;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.email = email;
        this.properties = properties;
        this.clock = clock;
        this.webUrl = publicWebUrl;
    }

    // ---- The row ---------------------------------------------------------------------------------------------------------

    record Sub(long id, long tenantId, String planCode, String period, String state, String fallbackPlan, Instant trialEndsAt,
               Instant trialReminder7, Instant trialReminder1, Instant periodStart, Instant paidUntil, Instant graceEndsAt, Instant graceReminder,
               String gatewaySubscriptionId, BigDecimal gatewayAmount, String checkoutId, String checkoutPlan, String checkoutPeriod,
               String pendingPlan, String pendingPeriod, String upgradeReference, String upgradePlan, BigDecimal upgradeAmount,
               String compPlan, Instant compUntil, String compReason, Instant lastEventAt) {}

    private static final String COLUMNS = """
            select id, tenant_id, plan_code, period, state, fallback_plan_code, trial_ends_at, trial_reminder_7_at, trial_reminder_1_at,
                   current_period_start, paid_until, grace_ends_at, grace_reminder_at, gateway_subscription_id, gateway_amount,
                   checkout_gateway_subscription_id, checkout_plan_code, checkout_period, pending_plan_code, pending_period,
                   upgrade_reference, upgrade_plan_code, upgrade_amount, comp_plan_code, comp_until, comp_reason, last_event_at
            from sf_subscriptions""";

    private static Sub row(ResultSet rs) throws SQLException {
        return new Sub(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), ts(rs, 7), ts(rs, 8), ts(rs, 9),
                ts(rs, 10), ts(rs, 11), ts(rs, 12), ts(rs, 13), rs.getString(14), rs.getBigDecimal(15), rs.getString(16), rs.getString(17), rs.getString(18),
                rs.getString(19), rs.getString(20), rs.getString(21), rs.getString(22), rs.getBigDecimal(23), rs.getString(24), ts(rs, 25), rs.getString(26),
                ts(rs, 27));
    }

    Optional<Sub> find(long tenantId) {
        return jdbc.sql(COLUMNS + " where tenant_id = :t").param("t", tenantId).query((rs, i) -> row(rs)).optional();
    }

    private Sub lock(long tenantId) {
        return jdbc.sql(COLUMNS + " where tenant_id = :t for update").param("t", tenantId).query((rs, i) -> row(rs)).optional()
                .orElseThrow(() -> ApiException.conflict("NO_SUBSCRIPTION", "This farm business has no subscription."));
    }

    Optional<Sub> byGatewayId(String gatewaySubscriptionId) {
        return jdbc.sql(COLUMNS + " where gateway = :g and (gateway_subscription_id = :id or checkout_gateway_subscription_id = :id) for update")
                .param("g", gateway.name()).param("id", gatewaySubscriptionId).query((rs, i) -> row(rs)).optional();
    }

    Optional<Sub> byUpgradeReference(String reference) {
        return jdbc.sql(COLUMNS + " where upgrade_reference = :r for update").param("r", reference).query((rs, i) -> row(rs)).optional();
    }

    /** A business without a subscription row gets one that has never been paid, keeping its current plan as the fallback. */
    private Sub ensure(long tenantId) {
        Optional<Sub> s = find(tenantId);
        if (s.isPresent()) return lock(tenantId);
        String current = jdbc.sql("select plan_code from sf_tenants where id = :t").param("t", tenantId).query(String.class).single();
        String fallback = catalog.paid(current) ? "FREE" : current;
        jdbc.sql("insert into sf_subscriptions (tenant_id, plan_code, state, fallback_plan_code) values (:t, :p, 'ENDED', :f)")
                .param("t", tenantId).param("p", fallback).param("f", fallback).update();
        return lock(tenantId);
    }

    // ---- The effective plan ----------------------------------------------------------------------------------------------

    static String effectivePlan(Sub s, Instant now) {
        if (s.compPlan() != null && s.compUntil() != null && s.compUntil().isAfter(now)) return s.compPlan();
        return switch (s.state()) {
            case "TRIAL" -> s.trialEndsAt() != null && s.trialEndsAt().isAfter(now) ? s.planCode() : s.fallbackPlan();
            case "ACTIVE", "PAST_DUE" -> s.planCode();
            case "CANCELLED" -> s.paidUntil() != null && s.paidUntil().isAfter(now) ? s.planCode() : s.fallbackPlan();
            default -> s.fallbackPlan();
        };
    }

    /** Writes the effective plan to the farm business, which {@code PlanLimits} reads. */
    private void applyPlan(long tenantId, Instant now) {
        Sub s = find(tenantId).orElseThrow();
        jdbc.sql("update sf_tenants set plan_code = :p, updated_at = now() where id = :t and plan_code <> :p").param("p", effectivePlan(s, now)).param("t", tenantId).update();
    }

    private void transition(Sub s, String to, String reason, Long actor, String gatewayEventId) {
        jdbc.sql("update sf_subscriptions set state = :s, updated_at = now() where id = :id").param("s", to).param("id", s.id()).update();
        Sub now = find(s.tenantId()).orElseThrow();
        jdbc.sql("""
                        insert into sf_subscription_events (tenant_id, subscription_id, from_state, to_state, plan_code, reason, actor_user_id, gateway_event_id)
                        values (:t, :s, :f, :to, :p, :r, :a, :g)""")
                .param("t", s.tenantId()).param("s", s.id()).param("f", s.state()).param("to", to).param("p", now.planCode()).param("r", reason)
                .param("a", actor).param("g", gatewayEventId).update();
        audit.record(s.tenantId(), actor, "BILLING_STATE_CHANGED", "SUBSCRIPTION", s.id(), Map.of("from", s.state(), "to", to, "plan", now.planCode(), "reason", reason));
    }

    // ---- Trial (P3) ------------------------------------------------------------------------------------------------------

    /**
     * A new farm business starts a 30-day GROWER trial, once per user. Returns the plan the business is on now.
     * Called in the same transaction that creates the business.
     */
    @Transactional
    public String startTrial(long tenantId, long userId) {
        int first = jdbc.sql("insert into sf_trials_used (user_id, first_tenant_id) values (:u, :t) on conflict (user_id) do nothing")
                .param("u", userId).param("t", tenantId).update();
        if (first == 0) return "FREE";
        Instant now = clock.instant();
        jdbc.sql("insert into sf_subscriptions (tenant_id, plan_code, state, fallback_plan_code, trial_ends_at) values (:t, 'GROWER', 'TRIAL', 'FREE', :end)")
                .param("t", tenantId).param("end", Timestamp.from(now.plus(Duration.ofDays(properties.trialDays)))).update();
        Sub s = find(tenantId).orElseThrow();
        jdbc.sql("insert into sf_subscription_events (tenant_id, subscription_id, to_state, plan_code, reason, actor_user_id) values (:t, :s, 'TRIAL', 'GROWER', 'New farm business', :u)")
                .param("t", tenantId).param("s", s.id()).param("u", userId).update();
        applyPlan(tenantId, now);
        return "GROWER";
    }

    // ---- What the owner sees -------------------------------------------------------------------------------------------

    public record Overview(String state, String planCode, String period, String effectivePlanCode, Instant trialEndsAt, Instant paidUntil,
                           Instant graceEndsAt, String pendingPlanCode, String pendingPeriod, String compPlanCode, Instant compUntil,
                           boolean checkoutStarted, boolean upgradeStarted, boolean profileComplete, String banner, List<PlanCatalog.Plan> plans) {}

    @Transactional(readOnly = true)
    public Overview overview(long tenantId) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, INDIA);
        boolean profile = jdbc.sql("select count(*) from sf_billing_profiles where tenant_id = :t").param("t", tenantId).query(Long.class).single() > 0;
        Optional<Sub> found = find(tenantId);
        String plan = jdbc.sql("select plan_code from sf_tenants where id = :t").param("t", tenantId).query(String.class).single();
        if (found.isEmpty()) {
            return new Overview("NONE", plan, null, plan, null, null, null, null, null, null, null, false, false, profile, null, catalog.plans(today));
        }
        Sub s = found.get();
        return new Overview(s.state(), s.planCode(), s.period(), effectivePlan(s, now), s.trialEndsAt(), s.paidUntil(), s.graceEndsAt(), s.pendingPlan(),
                s.pendingPeriod(), s.compUntil() != null && s.compUntil().isAfter(now) ? s.compPlan() : null, s.compUntil(), s.checkoutId() != null,
                s.upgradeReference() != null, profile, banner(s, now), catalog.plans(today));
    }

    /** The banner shown to every member: the trial's last week, a failed renewal, or a lapsed plan. */
    static String banner(Sub s, Instant now) {
        return switch (s.state()) {
            case "TRIAL" -> s.trialEndsAt() != null && s.trialEndsAt().isAfter(now) && Duration.between(now, s.trialEndsAt()).toDays() < 7 ? "TRIAL_ENDING" : null;
            case "PAST_DUE" -> "PAST_DUE";
            case "LAPSED" -> "LAPSED";
            default -> null;
        };
    }

    // ---- Checkout (P4) ---------------------------------------------------------------------------------------------------

    public record CheckoutResult(String checkoutUrl, Overview overview) {}

    /** Starts a subscription on the gateway's hosted page. Nothing changes here until the gateway's webhook confirms it. */
    @Transactional
    public CheckoutResult checkout(TenantContext ctx, String planCode, String period) {
        PlanCatalog.requirePeriod(period);
        if (!catalog.paid(planCode)) throw ApiException.badRequest("PLAN_NOT_FOR_SALE", "Choose a paid plan.");
        Map<String, Object> profile = requireProfile(ctx.tenantId());
        Sub s = ensure(ctx.tenantId());
        Instant now = clock.instant();
        if (List.of("ACTIVE", "PAST_DUE").contains(s.state()) || ("CANCELLED".equals(s.state()) && s.paidUntil() != null && s.paidUntil().isAfter(now))) {
            throw ApiException.conflict("SUBSCRIPTION_EXISTS", "This farm business already has a plan. Change it instead.");
        }
        BigDecimal gross = gross(planCode, period, (String) profile.get("state_code"), LocalDate.ofInstant(now, INDIA));
        String gatewayPlan = gatewayPlan(planCode, period, gross);
        PaymentGateway.Checkout c = gateway.createSubscription(gatewayPlan, period, Map.of("tenant_id", String.valueOf(ctx.tenantId()), "plan", planCode));
        jdbc.sql("""
                        update sf_subscriptions set gateway = :g, checkout_gateway_subscription_id = :c, checkout_plan_code = :p, checkout_period = :per,
                          checkout_started_at = :now, updated_at = now() where id = :id""")
                .param("g", gateway.name()).param("c", c.gatewayId()).param("p", planCode).param("per", period).param("now", Timestamp.from(now))
                .param("id", s.id()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "BILLING_CHECKOUT_STARTED", "SUBSCRIPTION", s.id(), Map.of("plan", planCode, "period", period, "amount", gross));
        return new CheckoutResult(c.url(), overview(ctx.tenantId()));
    }

    private Map<String, Object> requireProfile(long tenantId) {
        return jdbc.sql("select state_code, email from sf_billing_profiles where tenant_id = :t").param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("BILLING_PROFILE_INCOMPLETE", "Fill in the billing details first."));
    }

    /** The amount charged for a plan to a business in a state: the price plus GST. */
    BigDecimal gross(String plan, String period, String buyerState, LocalDate on) {
        return Gst.taxes(catalog.requirePrice(plan, period, on), invoices.sellerState(), buyerState, catalog.rates(on)).total();
    }

    /** The gateway's plan for a recurring amount, created the first time it is needed. */
    String gatewayPlan(String plan, String period, BigDecimal amount) {
        Optional<String> known = jdbc.sql("select gateway_plan_id from sf_gateway_plans where gateway = :g and plan_code = :p and period = :per and amount = :a")
                .param("g", gateway.name()).param("p", plan).param("per", period).param("a", amount).query(String.class).optional();
        if (known.isPresent()) return known.get();
        String id = gateway.createPlan(plan, period, amount, "SmartFarm360 " + catalog.name(plan) + " (" + period.toLowerCase() + ")");
        jdbc.sql("insert into sf_gateway_plans (gateway, plan_code, period, amount, gateway_plan_id) values (:g, :p, :per, :a, :id) on conflict do nothing")
                .param("g", gateway.name()).param("p", plan).param("per", period).param("a", amount).param("id", id).update();
        return id;
    }

    // ---- Changing plans and cancelling (P10) ---------------------------------------------------------------------------

    /**
     * Upgrading to a higher plan for the same period takes effect once its pro-rata part is paid (a one-off payment on
     * the gateway's page); everything else (a lower plan, or another period) takes effect at the next renewal.
     */
    @Transactional
    public CheckoutResult change(TenantContext ctx, String planCode, String period) {
        PlanCatalog.requirePeriod(period);
        if (!catalog.paid(planCode)) throw ApiException.badRequest("PLAN_NOT_FOR_SALE", "Choose a paid plan. To stop paying, cancel the plan.");
        Map<String, Object> profile = requireProfile(ctx.tenantId());
        Sub s = lock(ctx.tenantId());
        if (!"ACTIVE".equals(s.state()) || s.gatewaySubscriptionId() == null) {
            throw ApiException.conflict("SUBSCRIPTION_NOT_ACTIVE", "Only a paid, active plan can be changed.");
        }
        if (planCode.equals(s.planCode()) && period.equals(s.period())) throw ApiException.badRequest("PLAN_UNCHANGED", "This is already the plan.");
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, INDIA);
        String state = (String) profile.get("state_code");
        boolean upgrade = period.equals(s.period()) && catalog.rank(planCode) > catalog.rank(s.planCode());
        if (upgrade) {
            BigDecimal taxable = proRata(s, planCode, today, now);
            BigDecimal gross = Gst.taxes(taxable, invoices.sellerState(), state, catalog.rates(today)).total();
            String reference = "upg_" + s.id() + "_" + now.getEpochSecond();
            PaymentGateway.Checkout c = gateway.createPaymentLink(gross, "SmartFarm360: upgrade to " + catalog.name(planCode) + " until "
                    + LocalDate.ofInstant(s.paidUntil(), INDIA), reference, (String) profile.get("email"));
            jdbc.sql("update sf_subscriptions set upgrade_reference = :r, upgrade_plan_code = :p, upgrade_amount = :a, updated_at = now() where id = :id")
                    .param("r", reference).param("p", planCode).param("a", taxable).param("id", s.id()).update();
            audit.record(ctx.tenantId(), ctx.userId(), "BILLING_UPGRADE_STARTED", "SUBSCRIPTION", s.id(), Map.of("plan", planCode, "amount", gross));
            return new CheckoutResult(c.url(), overview(ctx.tenantId()));
        }
        BigDecimal next = gross(planCode, period, state, s.paidUntil() == null ? today : LocalDate.ofInstant(s.paidUntil(), INDIA));
        gateway.changePlanAtCycleEnd(s.gatewaySubscriptionId(), gatewayPlan(planCode, period, next));
        jdbc.sql("update sf_subscriptions set pending_plan_code = :p, pending_period = :per, gateway_amount = :a, updated_at = now() where id = :id")
                .param("p", planCode).param("per", period).param("a", next).param("id", s.id()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "BILLING_CHANGE_SCHEDULED", "SUBSCRIPTION", s.id(), Map.of("plan", planCode, "period", period));
        return new CheckoutResult(null, overview(ctx.tenantId()));
    }

    /** The upgrade's price for the rest of the paid period: the difference of the two prices, by days left. */
    BigDecimal proRata(Sub s, String newPlan, LocalDate today, Instant now) {
        BigDecimal oldPrice = catalog.requirePrice(s.planCode(), s.period(), today);
        BigDecimal newPrice = catalog.requirePrice(newPlan, s.period(), today);
        Instant start = s.periodStart() != null ? s.periodStart() : s.paidUntil().minus(Duration.ofDays("YEARLY".equals(s.period()) ? 365 : 30));
        long periodDays = Math.max(1, Duration.between(start, s.paidUntil()).toDays());
        long daysLeft = Math.max(1, (long) Math.ceil(Duration.between(now, s.paidUntil()).toHours() / 24.0));
        BigDecimal value = newPrice.subtract(oldPrice).multiply(BigDecimal.valueOf(Math.min(daysLeft, periodDays)))
                .divide(BigDecimal.valueOf(periodDays), 2, RoundingMode.HALF_UP);
        if (value.signum() <= 0) throw ApiException.badRequest("PLAN_UNCHANGED", "The new plan does not cost more; it changes at renewal.");
        return value;
    }

    /** Stops renewals; the plan stays until the paid period ends. A renewal that has not been paid is stopped at once. */
    @Transactional
    public Overview cancel(TenantContext ctx) {
        Sub s = lock(ctx.tenantId());
        if (!List.of("ACTIVE", "PAST_DUE").contains(s.state())) throw ApiException.conflict("SUBSCRIPTION_NOT_ACTIVE", "There is no paid plan to cancel.");
        boolean overdue = "PAST_DUE".equals(s.state());
        if (s.gatewaySubscriptionId() != null) gateway.cancel(s.gatewaySubscriptionId(), !overdue);
        jdbc.sql("update sf_subscriptions set pending_plan_code = null, pending_period = null, grace_ends_at = null, updated_at = now() where id = :id")
                .param("id", s.id()).update();
        transition(s, overdue ? "ENDED" : "CANCELLED", "Cancelled by an owner", ctx.userId(), null);
        applyPlan(ctx.tenantId(), clock.instant());
        return overview(ctx.tenantId());
    }

    /** Deleting a farm business ends its subscription at once (P11); cancelling the deletion does not restore it. */
    @Transactional
    public void endForDeletion(long tenantId, long userId) {
        Optional<Sub> found = find(tenantId);
        if (found.isEmpty()) return;
        Sub s = lock(tenantId);
        if ("ENDED".equals(s.state()) && s.checkoutId() == null && s.compPlan() == null) return;
        List<String> stop = new java.util.ArrayList<>();
        if (s.gatewaySubscriptionId() != null && List.of("ACTIVE", "PAST_DUE", "CANCELLED").contains(s.state())) stop.add(s.gatewaySubscriptionId());
        if (s.checkoutId() != null) stop.add(s.checkoutId());
        jdbc.sql("""
                        update sf_subscriptions set checkout_gateway_subscription_id = null, checkout_plan_code = null, checkout_period = null, pending_plan_code = null,
                          pending_period = null, upgrade_reference = null, upgrade_plan_code = null, upgrade_amount = null, comp_plan_code = null, comp_until = null,
                          comp_reason = null, grace_ends_at = null, updated_at = now() where id = :id""").param("id", s.id()).update();
        transition(s, "ENDED", "The farm business is being deleted", userId, null);
        applyPlan(tenantId, clock.instant());
        AfterCommit.run(() -> stop.forEach(id -> {
            try {
                gateway.cancel(id, false);
            } catch (RuntimeException e) {
                log.warn("Could not stop gateway subscription {} of tenant {}; the reconciliation lists it", id, tenantId);
            }
        }));
    }

    // ---- Gateway events (P4, P9) ---------------------------------------------------------------------------------------

    /** Applies one signed, not yet seen gateway event. Returns the outcome stored with it. */
    @Transactional
    public String apply(GatewayEvent e, long storedEventId) {
        Optional<Sub> found = e.kind() == GatewayEvent.Kind.LINK_PAID
                ? Optional.ofNullable(e.reference()).flatMap(this::byUpgradeReference)
                : Optional.ofNullable(e.subscriptionId()).flatMap(this::byGatewayId);
        if (found.isEmpty()) return e.kind() == GatewayEvent.Kind.OTHER ? "IGNORED" : "UNMATCHED";
        Sub s = found.get();
        jdbc.sql("update sf_gateway_events set customer_tenant_id = :t where id = :id").param("t", s.tenantId()).param("id", storedEventId).update();
        boolean stale = s.lastEventAt() != null && e.createdAt().isBefore(s.lastEventAt());
        Instant now = clock.instant();
        String outcome = switch (e.kind()) {
            case ACTIVATED -> stale ? "IGNORED" : activated(s, e);
            case CHARGED -> charged(s, e, stale);
            case PAYMENT_FAILED, HALTED -> stale ? "IGNORED" : failed(s, e, now);
            case CANCELLED -> stale ? "IGNORED" : cancelledAtGateway(s, e);
            case LINK_PAID -> upgradePaid(s, e);
            case OTHER -> "IGNORED";
        };
        if (!stale && e.kind() != GatewayEvent.Kind.OTHER) {
            jdbc.sql("update sf_subscriptions set last_event_at = :at where id = :id").param("at", Timestamp.from(e.createdAt())).param("id", s.id()).update();
        }
        applyPlan(s.tenantId(), now);
        return outcome;
    }

    /** The checkout's subscription becomes the business's subscription. */
    private Sub promote(Sub s) {
        if (s.checkoutId() == null) return s;
        String state = jdbc.sql("select state_code from sf_billing_profiles where tenant_id = :t").param("t", s.tenantId()).query(String.class).optional().orElse(invoices.sellerState());
        BigDecimal amount = gross(s.checkoutPlan(), s.checkoutPeriod(), state, LocalDate.ofInstant(clock.instant(), INDIA));
        jdbc.sql("""
                        update sf_subscriptions set gateway_subscription_id = checkout_gateway_subscription_id, plan_code = checkout_plan_code, period = checkout_period,
                          gateway_amount = :a, checkout_gateway_subscription_id = null, checkout_plan_code = null, checkout_period = null, checkout_started_at = null,
                          pending_plan_code = null, pending_period = null, grace_ends_at = null, grace_reminder_at = null, updated_at = now() where id = :id""")
                .param("a", amount).param("id", s.id()).update();
        // A subscription that lapsed or ended earlier must not charge again next to the new one
        String old = s.gatewaySubscriptionId();
        if (old != null && !old.equals(s.checkoutId())) {
            AfterCommit.run(() -> {
                try {
                    gateway.cancel(old, false);
                } catch (RuntimeException e) {
                    log.warn("Could not stop the earlier gateway subscription {} of tenant {}; the reconciliation lists it", old, s.tenantId());
                }
            });
        }
        return find(s.tenantId()).orElseThrow();
    }

    private String activated(Sub s, GatewayEvent e) {
        boolean wasCheckout = e.subscriptionId().equals(s.checkoutId());
        Sub p = wasCheckout ? promote(s) : s;
        if (e.periodEnd() != null) extendPaid(p, e.periodStart(), e.periodEnd());
        if (!"ACTIVE".equals(s.state()) || wasCheckout) {
            transition(s, "ACTIVE", wasCheckout ? "Subscribed" : "Active again at the gateway", null, e.eventId());
            if (wasCheckout) platformAudit.record(null, "BILLING_SUBSCRIBED", "TENANT", s.tenantId(), Map.of("plan", p.planCode(), "period", p.period()));
        }
        return "APPLIED";
    }

    private String charged(Sub s, GatewayEvent e, boolean stale) {
        if (e.paymentId() == null || e.amount() == null) return "IGNORED";
        boolean wasCheckout = e.subscriptionId().equals(s.checkoutId());
        Sub p = wasCheckout ? promote(s) : s;
        Instant periodStart = e.periodStart() != null ? e.periodStart() : p.paidUntil() != null && p.paidUntil().isAfter(e.createdAt()) ? p.paidUntil() : e.createdAt();
        Instant periodEnd = e.periodEnd() != null ? e.periodEnd() : periodStart.atZone(INDIA).plus("YEARLY".equals(p.period()) ? java.time.Period.ofYears(1) : java.time.Period.ofMonths(1)).toInstant();
        // A renewal for a new period takes the change the owner scheduled
        if (p.pendingPlan() != null && (p.paidUntil() == null || !periodStart.isBefore(p.paidUntil().minus(Duration.ofHours(1))))) {
            jdbc.sql("update sf_subscriptions set plan_code = pending_plan_code, period = pending_period, pending_plan_code = null, pending_period = null where id = :id")
                    .param("id", p.id()).update();
            p = find(p.tenantId()).orElseThrow();
        }
        Optional<Long> paymentId = recordPayment(p.tenantId(), e, "RENEWAL", p.gatewaySubscriptionId());
        if (paymentId.isPresent()) {
            InvoiceService.Invoice inv = invoices.issue(p.tenantId(), paymentId.get(), LocalDate.ofInstant(e.createdAt(), INDIA),
                    new InvoiceService.Line("SmartFarm360 " + catalog.name(p.planCode()) + " plan (" + p.period().toLowerCase() + ")", p.planCode(), p.period(),
                            LocalDate.ofInstant(periodStart, INDIA), LocalDate.ofInstant(periodEnd, INDIA).minusDays(1)));
            notifyOwners(p.tenantId(), EmailSender.Notice.PAYMENT_RECEIVED, Map.of("number", inv.number(), "amount", inv.total().toPlainString()));
        }
        extendPaid(p, periodStart, periodEnd);
        if (!stale || wasCheckout) {
            jdbc.sql("update sf_subscriptions set grace_ends_at = null, grace_reminder_at = null where id = :id").param("id", p.id()).update();
            if (!"ACTIVE".equals(s.state()) || wasCheckout) transition(s, "ACTIVE", wasCheckout ? "Subscribed" : "Renewal paid", null, e.eventId());
            if (wasCheckout) platformAudit.record(null, "BILLING_SUBSCRIBED", "TENANT", s.tenantId(), Map.of("plan", p.planCode(), "period", p.period()));
        }
        return paymentId.isPresent() ? "APPLIED" : "IGNORED";
    }

    private void extendPaid(Sub s, Instant start, Instant end) {
        jdbc.sql("""
                        update sf_subscriptions set paid_until = greatest(coalesce(paid_until, :end), :end),
                          current_period_start = case when paid_until is null or :end >= paid_until then coalesce(:start, current_period_start) else current_period_start end
                        where id = :id""")
                .param("end", Timestamp.from(end)).param("start", start == null ? null : Timestamp.from(start)).param("id", s.id()).update();
    }

    private Optional<Long> recordPayment(long tenantId, GatewayEvent e, String kind, String gatewaySubscriptionId) {
        return jdbc.sql("""
                        insert into sf_payments (customer_tenant_id, gateway, gateway_payment_id, gateway_subscription_id, kind, amount, method, captured_at)
                        values (:t, :g, :p, :s, :k, :a, :m, :at) on conflict (gateway, gateway_payment_id) do nothing returning id""")
                .param("t", tenantId).param("g", gateway.name()).param("p", e.paymentId()).param("s", gatewaySubscriptionId).param("k", kind)
                .param("a", Gst.money(e.amount())).param("m", e.method()).param("at", Timestamp.from(e.createdAt())).query(Long.class).optional();
    }

    private String failed(Sub s, GatewayEvent e, Instant now) {
        if (!"ACTIVE".equals(s.state())) return "IGNORED";
        Instant graceEnd = now.plus(Duration.ofDays(properties.graceDays));
        jdbc.sql("update sf_subscriptions set grace_ends_at = :g, grace_reminder_at = null where id = :id").param("g", Timestamp.from(graceEnd)).param("id", s.id()).update();
        transition(s, "PAST_DUE", "Renewal failed", null, e.eventId());
        notifyOwners(s.tenantId(), EmailSender.Notice.RENEWAL_FAILED, Map.of("date", LocalDate.ofInstant(graceEnd, INDIA).toString()));
        return "APPLIED";
    }

    private String cancelledAtGateway(Sub s, GatewayEvent e) {
        if (!e.subscriptionId().equals(s.gatewaySubscriptionId()) || !"ACTIVE".equals(s.state())) return "IGNORED";
        transition(s, "CANCELLED", "Ended at the gateway", null, e.eventId());
        return "APPLIED";
    }

    private String upgradePaid(Sub s, GatewayEvent e) {
        if (e.paymentId() == null || e.amount() == null) return "IGNORED";
        Optional<Long> paymentId = recordPayment(s.tenantId(), e, "UPGRADE", s.gatewaySubscriptionId());
        if (paymentId.isEmpty()) return "IGNORED";
        String plan = s.upgradePlan();
        LocalDate today = LocalDate.ofInstant(e.createdAt(), INDIA);
        InvoiceService.Invoice inv = invoices.issue(s.tenantId(), paymentId.get(), today, new InvoiceService.Line("SmartFarm360 upgrade from "
                + catalog.name(s.planCode()) + " to " + catalog.name(plan) + " for the rest of the period", plan, s.period(), today,
                LocalDate.ofInstant(s.paidUntil(), INDIA).minusDays(1)));
        String state = jdbc.sql("select state_code from sf_billing_profiles where tenant_id = :t").param("t", s.tenantId()).query(String.class).single();
        BigDecimal next = gross(plan, s.period(), state, LocalDate.ofInstant(s.paidUntil(), INDIA));
        gateway.changePlanAtCycleEnd(s.gatewaySubscriptionId(), gatewayPlan(plan, s.period(), next));
        jdbc.sql("""
                        update sf_subscriptions set plan_code = :p, gateway_amount = :a, upgrade_reference = null, upgrade_plan_code = null, upgrade_amount = null,
                          pending_plan_code = null, pending_period = null, updated_at = now() where id = :id""")
                .param("p", plan).param("a", next).param("id", s.id()).update();
        audit.record(s.tenantId(), null, "BILLING_UPGRADED", "SUBSCRIPTION", s.id(), Map.of("from", s.planCode(), "to", plan, "invoice", inv.number()));
        notifyOwners(s.tenantId(), EmailSender.Notice.PAYMENT_RECEIVED, Map.of("number", inv.number(), "amount", inv.total().toPlainString()));
        return "APPLIED";
    }

    // ---- The daily job (P3, P9) ----------------------------------------------------------------------------------------

    @Scheduled(cron = "${app.jobs.billing-cron:0 15 2 * * *}")
    public void scheduledDaily() { runDaily(clock.instant()); }

    /** Trial reminders and ends, grace reminders and lapses, ended cancellations and comps, and announced price changes. */
    public void runDaily(Instant now) {
        List<Long> tenants = jdbc.sql("select tenant_id from sf_subscriptions order by tenant_id").query(Long.class).list();
        for (long t : tenants) {
            try {
                daily(t, now);
            } catch (RuntimeException e) {
                log.error("Daily billing work for tenant {} failed", t, e);
            }
        }
    }

    @Transactional
    public void daily(long tenantId, Instant now) {
        Sub s = lock(tenantId);
        Map<String, String> tenant = Map.of("tenant", tenantName(tenantId));
        if ("TRIAL".equals(s.state()) && s.trialEndsAt() != null) {
            Duration left = Duration.between(now, s.trialEndsAt());
            if (!left.isNegative() && !left.isZero()) {
                if (left.compareTo(Duration.ofDays(1)) <= 0 && s.trialReminder1() == null) {
                    remind(s, "trial_reminder_1_at", now, EmailSender.Notice.TRIAL_ENDING, Map.of("days", "1", "date", LocalDate.ofInstant(s.trialEndsAt(), INDIA).toString()));
                } else if (left.compareTo(Duration.ofDays(7)) <= 0 && left.compareTo(Duration.ofDays(1)) > 0 && s.trialReminder7() == null) {
                    remind(s, "trial_reminder_7_at", now, EmailSender.Notice.TRIAL_ENDING, Map.of("days", "7", "date", LocalDate.ofInstant(s.trialEndsAt(), INDIA).toString()));
                }
            } else {
                transition(s, "ENDED", "The trial ended", null, null);
                notifyOwners(tenantId, EmailSender.Notice.TRIAL_ENDED, tenant);
            }
        }
        if ("PAST_DUE".equals(s.state()) && s.graceEndsAt() != null) {
            if (!s.graceEndsAt().isAfter(now)) {
                transition(s, "LAPSED", "Grace ended without payment", null, null);
                notifyOwners(tenantId, EmailSender.Notice.PLAN_LAPSED, tenant);
            } else if (Duration.between(now, s.graceEndsAt()).compareTo(GRACE_REMINDER) <= 0 && s.graceReminder() == null) {
                remind(s, "grace_reminder_at", now, EmailSender.Notice.GRACE_ENDING, Map.of("date", LocalDate.ofInstant(s.graceEndsAt(), INDIA).toString()));
            }
        }
        if ("CANCELLED".equals(s.state()) && (s.paidUntil() == null || !s.paidUntil().isAfter(now))) {
            transition(s, "ENDED", "The paid period ended", null, null);
        }
        if (s.compUntil() != null && !s.compUntil().isAfter(now)) {
            jdbc.sql("update sf_subscriptions set comp_plan_code = null, comp_until = null, comp_reason = null where id = :id").param("id", s.id()).update();
            audit.record(tenantId, null, "BILLING_COMP_ENDED", "SUBSCRIPTION", s.id(), Map.of("plan", s.compPlan()));
        }
        applyPriceChange(lock(tenantId));
        applyPlan(tenantId, now);
    }

    /** A price change applies from the first renewal on or after its date: the gateway is told to charge the new amount then. */
    private void applyPriceChange(Sub s) {
        if (!"ACTIVE".equals(s.state()) || s.gatewaySubscriptionId() == null || s.paidUntil() == null) return;
        Optional<String> state = jdbc.sql("select state_code from sf_billing_profiles where tenant_id = :t").param("t", s.tenantId()).query(String.class).optional();
        if (state.isEmpty()) return;
        String plan = s.pendingPlan() != null ? s.pendingPlan() : s.planCode();
        String period = s.pendingPeriod() != null ? s.pendingPeriod() : s.period();
        BigDecimal next = gross(plan, period, state.get(), LocalDate.ofInstant(s.paidUntil(), INDIA));
        if (next.compareTo(s.gatewayAmount() == null ? BigDecimal.ZERO : s.gatewayAmount()) == 0) return;
        gateway.changePlanAtCycleEnd(s.gatewaySubscriptionId(), gatewayPlan(plan, period, next));
        jdbc.sql("update sf_subscriptions set gateway_amount = :a where id = :id").param("a", next).param("id", s.id()).update();
        audit.record(s.tenantId(), null, "BILLING_PRICE_APPLIED", "SUBSCRIPTION", s.id(), Map.of("amount", next));
    }

    private void remind(Sub s, String column, Instant now, EmailSender.Notice notice, Map<String, String> params) {
        jdbc.sql("update sf_subscriptions set " + column + " = :at where id = :id").param("at", Timestamp.from(now)).param("id", s.id()).update();
        Map<String, String> p = new HashMap<>(params);
        p.put("tenant", tenantName(s.tenantId()));
        notifyOwners(s.tenantId(), notice, p);
    }

    // ---- Platform administrator actions (P12) --------------------------------------------------------------------------

    @Transactional
    public void extendTrial(CurrentUser admin, long tenantId, int days, String reason) {
        Sub s = lock(tenantId);
        if (!"TRIAL".equals(s.state())) throw ApiException.conflict("NOT_IN_TRIAL", "This farm business is not in its trial.");
        jdbc.sql("update sf_subscriptions set trial_ends_at = trial_ends_at + make_interval(days => :d), trial_reminder_7_at = null, trial_reminder_1_at = null where id = :id")
                .param("d", days).param("id", s.id()).update();
        platformAudit.record(admin.id(), "BILLING_TRIAL_EXTENDED", "TENANT", tenantId, Map.of("days", days, "reason", reason));
        audit.record(tenantId, admin.id(), "BILLING_TRIAL_EXTENDED", "SUBSCRIPTION", s.id(), Map.of("days", days));
        applyPlan(tenantId, clock.instant());
    }

    /** Extends the grace of a failed renewal, or reopens it for a business whose plan lapsed. */
    @Transactional
    public void extendGrace(CurrentUser admin, long tenantId, int days, String reason) {
        Sub s = lock(tenantId);
        Instant now = clock.instant();
        if ("PAST_DUE".equals(s.state())) {
            jdbc.sql("update sf_subscriptions set grace_ends_at = grace_ends_at + make_interval(days => :d), grace_reminder_at = null where id = :id")
                    .param("d", days).param("id", s.id()).update();
        } else if ("LAPSED".equals(s.state())) {
            jdbc.sql("update sf_subscriptions set grace_ends_at = :g, grace_reminder_at = null where id = :id")
                    .param("g", Timestamp.from(now.plus(Duration.ofDays(days)))).param("id", s.id()).update();
            transition(s, "PAST_DUE", "Grace reopened by SmartFarm360: " + reason, admin.id(), null);
        } else {
            throw ApiException.conflict("NOT_PAST_DUE", "This farm business has no unpaid renewal.");
        }
        platformAudit.record(admin.id(), "BILLING_GRACE_EXTENDED", "TENANT", tenantId, Map.of("days", days, "reason", reason));
        audit.record(tenantId, admin.id(), "BILLING_GRACE_EXTENDED", "SUBSCRIPTION", s.id(), Map.of("days", days));
        applyPlan(tenantId, now);
    }

    /** Gives a plan free until a date, for example for a pilot farm; it ends on its own. */
    @Transactional
    public void comp(CurrentUser admin, long tenantId, String planCode, LocalDate until, String reason) {
        if (!catalog.paid(planCode)) throw ApiException.badRequest("PLAN_NOT_FOR_SALE", "Choose a paid plan.");
        Instant end = until.plusDays(1).atStartOfDay(INDIA).toInstant();
        if (!end.isAfter(clock.instant())) throw ApiException.badRequest("DATE_IN_PAST", "Choose a date from today.");
        Sub s = ensure(tenantId);
        jdbc.sql("update sf_subscriptions set comp_plan_code = :p, comp_until = :u, comp_reason = :r, updated_at = now() where id = :id")
                .param("p", planCode).param("u", Timestamp.from(end)).param("r", reason).param("id", s.id()).update();
        platformAudit.record(admin.id(), "BILLING_COMP_GIVEN", "TENANT", tenantId, Map.of("plan", planCode, "until", until.toString(), "reason", reason));
        audit.record(tenantId, admin.id(), "BILLING_COMP_GIVEN", "SUBSCRIPTION", s.id(), Map.of("plan", planCode, "until", until.toString()));
        applyPlan(tenantId, clock.instant());
    }

    @Transactional
    public void endComp(CurrentUser admin, long tenantId, String reason) {
        Sub s = lock(tenantId);
        if (s.compPlan() == null) throw ApiException.conflict("NO_COMP", "This farm business has no free plan to end.");
        jdbc.sql("update sf_subscriptions set comp_plan_code = null, comp_until = null, comp_reason = null where id = :id").param("id", s.id()).update();
        platformAudit.record(admin.id(), "BILLING_COMP_ENDED", "TENANT", tenantId, Map.of("reason", reason));
        audit.record(tenantId, admin.id(), "BILLING_COMP_ENDED", "SUBSCRIPTION", s.id(), Map.of("plan", s.compPlan()));
        applyPlan(tenantId, clock.instant());
    }

    public record ConsoleSubscription(long tenantId, String tenantName, String state, String planCode, String period, String effectivePlanCode,
                                      Instant trialEndsAt, Instant paidUntil, Instant graceEndsAt, String compPlanCode, Instant compUntil, String compReason) {}

    @Transactional(readOnly = true)
    public List<ConsoleSubscription> consoleList(String state) {
        Instant now = clock.instant();
        return jdbc.sql(COLUMNS + (state == null || state.isBlank() ? "" : " where state = :s") + " order by updated_at desc limit 200")
                .params(state == null || state.isBlank() ? Map.of() : Map.of("s", state)).query((rs, i) -> row(rs)).list().stream()
                .map(s -> new ConsoleSubscription(s.tenantId(), tenantName(s.tenantId()), s.state(), s.planCode(), s.period(), effectivePlan(s, now),
                        s.trialEndsAt(), s.paidUntil(), s.graceEndsAt(), s.compPlan(), s.compUntil(), s.compReason())).toList();
    }

    // ---- Emails ----------------------------------------------------------------------------------------------------------

    void notifyOwners(long tenantId, EmailSender.Notice notice, Map<String, String> params) {
        Map<String, String> p = new HashMap<>(params);
        p.putIfAbsent("tenant", tenantName(tenantId));
        p.putIfAbsent("url", webUrl + "/t/" + tenantId + "/settings#billing");
        List<Map<String, Object>> owners = jdbc.sql("""
                        select distinct u.email, u.preferred_language from sf_tenant_memberships m join sf_users u on u.id = m.user_id
                        join sf_membership_roles mr on mr.membership_id = m.id join sf_roles r on r.id = mr.role_id
                        where m.tenant_id = :t and m.active and r.code = 'TENANT_OWNER' and u.active""").param("t", tenantId).query().listOfRows();
        AfterCommit.run(() -> owners.forEach(o -> email.sendNotice((String) o.get("email"), (String) o.get("preferred_language"), notice, p)));
    }

    private String tenantName(long tenantId) {
        return jdbc.sql("select name from sf_tenants where id = :t").param("t", tenantId).query(String.class).optional().orElse("");
    }

    private static Instant ts(ResultSet rs, int column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
