package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.admin.PlatformAudit;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.mail.EmailSender;
import com.smartfarm360.platform.security.CurrentUser;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Billing details, invoices for owners and accountants, refunds and prices for platform administrators
 * (billing design, sections 5, 6, 8 and 9).
 */
@Service
public class BillingService {
    private final JdbcClient jdbc;
    private final InvoiceService invoices;
    private final SubscriptionService subscriptions;
    private final PaymentGateway gateway;
    private final PlanCatalog catalog;
    private final AuditService audit;
    private final PlatformAudit platformAudit;
    private final Clock clock;

    public BillingService(JdbcClient jdbc, InvoiceService invoices, SubscriptionService subscriptions, PaymentGateway gateway, PlanCatalog catalog,
                          AuditService audit, PlatformAudit platformAudit, Clock clock) {
        this.jdbc = jdbc;
        this.invoices = invoices;
        this.subscriptions = subscriptions;
        this.gateway = gateway;
        this.catalog = catalog;
        this.audit = audit;
        this.platformAudit = platformAudit;
        this.clock = clock;
    }

    // ---- Billing details -----------------------------------------------------------------------------------------------

    public record Profile(String legalName, String addressLine1, String addressLine2, String city, String pincode, String stateCode, String gstin,
                          String email, Instant updatedAt) {}

    @Transactional(readOnly = true)
    public Optional<Profile> profile(TenantContext ctx) {
        return jdbc.sql("select legal_name, address_line1, address_line2, city, pincode, state_code, gstin, email, updated_at from sf_billing_profiles where tenant_id = :t")
                .param("t", ctx.tenantId()).query((rs, i) -> new Profile(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getTimestamp(9).toInstant())).optional();
    }

    /** Saves the details printed on the next invoices. Issued invoices keep the details they were issued with. */
    @Transactional
    public Profile saveProfile(TenantContext ctx, Profile p) {
        if (!Gst.STATES.containsKey(p.stateCode())) throw ApiException.badRequest("UNKNOWN_STATE", "Choose the state of the billing address.");
        String gstin = p.gstin() == null || p.gstin().isBlank() ? null : p.gstin().trim().toUpperCase();
        if (gstin != null && !Gst.validGstin(gstin)) throw ApiException.badRequest("INVALID_GSTIN", "This GSTIN is not valid. Check it, or leave it empty.");
        if (gstin != null && !gstin.startsWith(p.stateCode())) throw ApiException.badRequest("GSTIN_STATE_MISMATCH", "The GSTIN belongs to another state.");
        jdbc.sql("""
                        insert into sf_billing_profiles (tenant_id, legal_name, address_line1, address_line2, city, pincode, state_code, gstin, email, updated_by)
                        values (:t, :n, :a1, :a2, :c, :pin, :s, :g, :e, :u)
                        on conflict (tenant_id) do update set legal_name = excluded.legal_name, address_line1 = excluded.address_line1,
                          address_line2 = excluded.address_line2, city = excluded.city, pincode = excluded.pincode, state_code = excluded.state_code,
                          gstin = excluded.gstin, email = excluded.email, updated_by = excluded.updated_by, updated_at = now()""")
                .param("t", ctx.tenantId()).param("n", p.legalName().trim()).param("a1", p.addressLine1().trim())
                .param("a2", p.addressLine2() == null || p.addressLine2().isBlank() ? null : p.addressLine2().trim()).param("c", p.city().trim())
                .param("pin", p.pincode()).param("s", p.stateCode()).param("g", gstin).param("e", p.email().trim()).param("u", ctx.userId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "BILLING_PROFILE_SAVED", "BILLING_PROFILE", ctx.tenantId(), Map.of("state", p.stateCode(), "gstin", gstin != null));
        return profile(ctx).orElseThrow();
    }

    // ---- Invoices ------------------------------------------------------------------------------------------------------

    public List<InvoiceService.Invoice> invoices(TenantContext ctx) { return invoices.list(ctx.tenantId()); }

    public record Pdf(String fileName, byte[] content) {}

    @Transactional(readOnly = true)
    public Pdf invoicePdf(TenantContext ctx, long invoiceId) {
        InvoiceService.Invoice inv = invoices.invoice(ctx.tenantId(), invoiceId);
        return new Pdf(inv.number().replace('/', '-') + ".pdf", InvoicePdf.invoice(inv));
    }

    @Transactional(readOnly = true)
    public Pdf creditNotePdf(TenantContext ctx, long invoiceId, long creditNoteId) {
        InvoiceService.Invoice inv = invoices.invoice(ctx.tenantId(), invoiceId);
        InvoiceService.CreditNote note = inv.creditNotes().stream().filter(c -> c.id() == creditNoteId).findFirst()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "Credit note not found."));
        return new Pdf(note.number().replace('/', '-') + ".pdf", InvoicePdf.creditNote(inv, note));
    }

    // ---- Refunds (P10) ---------------------------------------------------------------------------------------------------

    public record PaymentRow(long id, long tenantId, String businessName, String kind, BigDecimal amount, BigDecimal refundedAmount, String method,
                             Instant capturedAt, Long invoiceId, String invoiceNumber) {}

    @Transactional(readOnly = true)
    public List<PaymentRow> payments(Long tenantId) {
        return jdbc.sql("""
                        select p.id, p.customer_tenant_id, coalesce(i.business_name, t.name, ''), p.kind, p.amount, p.refunded_amount, p.method, p.captured_at, i.id, i.number
                        from sf_payments p left join sf_invoices i on i.payment_id = p.id left join sf_tenants t on t.id = p.customer_tenant_id
                        where (cast(:t as bigint) is null or p.customer_tenant_id = :t) order by p.captured_at desc, p.id desc limit 200""")
                .param("t", tenantId).query((rs, i) -> new PaymentRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getTimestamp(8).toInstant(), (Long) rs.getObject(9), rs.getString(10))).list();
    }

    /** Refunds part or all of a payment through the gateway, with a credit note. Only platform administrators, with a reason. */
    @Transactional
    public InvoiceService.CreditNote refund(CurrentUser admin, long paymentId, BigDecimal amount, String reason) {
        Map<String, Object> p = jdbc.sql("""
                        select p.customer_tenant_id, p.gateway_payment_id, p.amount, p.refunded_amount, i.id as invoice_id
                        from sf_payments p join sf_invoices i on i.payment_id = p.id where p.id = :p for update of p""")
                .param("p", paymentId).query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found."));
        BigDecimal refundable = ((BigDecimal) p.get("amount")).subtract((BigDecimal) p.get("refunded_amount"));
        BigDecimal value = Gst.money(amount);
        if (value.signum() <= 0 || value.compareTo(refundable) > 0) {
            throw ApiException.badRequest("REFUND_TOO_LARGE", "The refund must be more than zero and at most what is left of the payment.");
        }
        String refundId = gateway.refund((String) p.get("gateway_payment_id"), value);
        long tenantId = ((Number) p.get("customer_tenant_id")).longValue();
        InvoiceService.CreditNote note = invoices.creditNote(((Number) p.get("invoice_id")).longValue(), value, reason, refundId, admin.id(),
                LocalDate.ofInstant(clock.instant(), SubscriptionService.INDIA));
        jdbc.sql("update sf_payments set refunded_amount = refunded_amount + :a where id = :p").param("a", value).param("p", paymentId).update();
        platformAudit.record(admin.id(), "BILLING_REFUNDED", "TENANT", tenantId, Map.of("payment", paymentId, "amount", value, "creditNote", note.number(), "reason", reason));
        audit.record(tenantId, admin.id(), "BILLING_REFUNDED", "PAYMENT", paymentId, Map.of("amount", value, "creditNote", note.number()));
        subscriptions.notifyOwners(tenantId, EmailSender.Notice.REFUND_ISSUED, Map.of("amount", value.toPlainString(), "number", note.number()));
        return note;
    }

    /** Sends the payment email of an invoice again, for example when the owner lost it. */
    @Transactional(readOnly = true)
    public void resendInvoice(CurrentUser admin, long invoiceId) {
        InvoiceService.Invoice inv = invoices.invoiceAnyTenant(invoiceId);
        long tenantId = jdbc.sql("select customer_tenant_id from sf_invoices where id = :i").param("i", invoiceId).query(Long.class).single();
        subscriptions.notifyOwners(tenantId, EmailSender.Notice.PAYMENT_RECEIVED, Map.of("number", inv.number(), "amount", inv.total().toPlainString()));
        platformAudit.record(admin.id(), "BILLING_INVOICE_RESENT", "TENANT", tenantId, Map.of("invoice", inv.number()));
    }

    // ---- Prices (P1) -----------------------------------------------------------------------------------------------------

    public record PriceRow(String planCode, String period, BigDecimal amount, LocalDate validFrom, Instant createdAt) {}

    @Transactional(readOnly = true)
    public List<PriceRow> prices() {
        return jdbc.sql("select plan_code, period, amount, valid_from, created_at from sf_plan_prices order by plan_code, period, valid_from desc")
                .query((rs, i) -> new PriceRow(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getDate(4).toLocalDate(), rs.getTimestamp(5).toInstant())).list();
    }

    /**
     * Announces a new price, before GST. It applies from a date at least 30 days away, so owners have 30 days' notice
     * by email; renewals from that date charge it.
     */
    @Transactional
    public PriceRow setPrice(CurrentUser admin, String planCode, String period, BigDecimal amount, LocalDate validFrom) {
        PlanCatalog.requirePeriod(period);
        if (!catalog.paid(planCode)) throw ApiException.badRequest("PLAN_NOT_FOR_SALE", "Only paid plans have prices.");
        LocalDate today = LocalDate.ofInstant(clock.instant(), SubscriptionService.INDIA);
        if (validFrom.isBefore(today.plusDays(30))) throw ApiException.badRequest("PRICE_NOTICE_TOO_SHORT", "A new price applies at least 30 days from today.");
        int n = jdbc.sql("insert into sf_plan_prices (plan_code, period, amount, valid_from, created_by) values (:p, :per, :a, :d, :u) on conflict do nothing")
                .param("p", planCode).param("per", period).param("a", Gst.money(amount)).param("d", Date.valueOf(validFrom)).param("u", admin.id()).update();
        if (n == 0) throw ApiException.conflict("PRICE_EXISTS", "This plan already has a price from that date.");
        platformAudit.record(admin.id(), "BILLING_PRICE_SET", "PLAN", null, Map.of("plan", planCode, "period", period, "amount", Gst.money(amount), "from", validFrom.toString()));
        List<Long> affected = jdbc.sql("""
                        select tenant_id from sf_subscriptions where state in ('ACTIVE', 'PAST_DUE')
                          and coalesce(pending_plan_code, plan_code) = :p and coalesce(pending_period, period) = :per""")
                .param("p", planCode).param("per", period).query(Long.class).list();
        Map<String, String> params = new HashMap<>(Map.of("plan", catalog.name(planCode), "amount", Gst.money(amount).toPlainString(), "date", validFrom.toString()));
        for (long t : affected) subscriptions.notifyOwners(t, EmailSender.Notice.PRICE_CHANGE, params);
        return prices().stream().filter(r -> r.planCode().equals(planCode) && r.period().equals(period) && r.validFrom().equals(validFrom)).findFirst().orElseThrow();
    }

    /** The console's billing summary: subscriptions by state. */
    @Transactional(readOnly = true)
    public Map<String, Long> countsByState() {
        Map<String, Long> out = new LinkedHashMap<>();
        for (String s : List.of("TRIAL", "ACTIVE", "PAST_DUE", "LAPSED", "CANCELLED", "ENDED")) out.put(s, 0L);
        jdbc.sql("select state, count(*) from sf_subscriptions group by state").query((rs, i) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                .forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
