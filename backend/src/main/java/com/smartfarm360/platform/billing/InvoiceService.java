package com.smartfarm360.platform.billing;

import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * GST tax invoices and credit notes (billing design, section 6, P6). An invoice is issued only for a captured
 * payment, its total is exactly the amount captured, and it is never changed afterwards (a trigger refuses it):
 * a refund is a credit note. Numbers are gapless per series and financial year, taken in the same transaction.
 */
@Service
public class InvoiceService {
    static final String INVOICE_SERIES = "INV";
    static final String CREDIT_NOTE_SERIES = "CN";

    private final JdbcClient jdbc;
    private final BillingProperties properties;
    private final PlanCatalog catalog;

    public InvoiceService(JdbcClient jdbc, BillingProperties properties, PlanCatalog catalog) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.catalog = catalog;
    }

    public record CreditNote(long id, String number, LocalDate noteDate, String reason, BigDecimal taxableAmount, BigDecimal cgst,
                             BigDecimal sgst, BigDecimal igst, BigDecimal total) {}

    public record Invoice(long id, String number, LocalDate invoiceDate, Instant issuedAt, String businessName, String buyerName, String buyerAddress,
                          String buyerGstin, String buyerStateCode, String sellerName, String sellerGstin, String sellerAddress, String sellerStateCode,
                          String sac, String description, String planCode, String period, LocalDate periodStart, LocalDate periodEnd,
                          BigDecimal taxableAmount, BigDecimal cgstRate, BigDecimal cgst, BigDecimal sgstRate, BigDecimal sgst, BigDecimal igstRate,
                          BigDecimal igst, BigDecimal total, BigDecimal refundedAmount, Long bookedDocumentId, List<CreditNote> creditNotes) {}

    /** What the invoice is for: one line. */
    public record Line(String description, String planCode, String period, LocalDate periodStart, LocalDate periodEnd) {}

    /** Issues the invoice for a captured payment, with the business's billing details as they are now. */
    @Transactional
    public Invoice issue(long tenantId, long paymentId, LocalDate invoiceDate, Line line) {
        Map<String, Object> pay = jdbc.sql("select amount from sf_payments where id = :p and customer_tenant_id = :t").param("p", paymentId).param("t", tenantId)
                .query().singleRow();
        BigDecimal amount = (BigDecimal) pay.get("amount");
        Map<String, Object> profile = jdbc.sql("""
                        select b.legal_name, b.address_line1, b.address_line2, b.city, b.pincode, b.state_code, b.gstin, b.email, t.name
                        from sf_billing_profiles b join sf_tenants t on t.id = b.tenant_id where b.tenant_id = :t""").param("t", tenantId).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("BILLING_PROFILE_INCOMPLETE", "Fill in the billing details first."));
        String buyerState = (String) profile.get("state_code");
        Gst.Taxes taxes = fromTotal(amount, sellerState(), buyerState, catalog.rates(invoiceDate));
        String fy = Gst.financialYear(invoiceDate);
        String number = Gst.number(properties.invoicePrefix, fy, next(INVOICE_SERIES, fy));
        String address = String.join(", ", java.util.stream.Stream.of(profile.get("address_line1"), profile.get("address_line2"), profile.get("city"),
                Gst.STATES.getOrDefault(buyerState, buyerState) + " " + profile.get("pincode")).filter(s -> s != null && !s.toString().isBlank()).map(Object::toString).toList());
        long id = jdbc.sql("""
                        insert into sf_invoices (customer_tenant_id, number, financial_year, invoice_date, payment_id, seller_name, seller_gstin, seller_address,
                          seller_state_code, buyer_name, buyer_address, buyer_state_code, buyer_gstin, buyer_email, business_name, sac, taxable_amount,
                          cgst, sgst, igst, total, cgst_rate, sgst_rate, igst_rate)
                        values (:t, :n, :fy, :d, :p, :sn, :sg, :sa, :ss, :bn, :ba, :bs, :bg, :be, :biz, :sac, :tx, :c, :s, :i, :tot, :cr, :sr, :ir) returning id""")
                .param("t", tenantId).param("n", number).param("fy", fy).param("d", Date.valueOf(invoiceDate)).param("p", paymentId)
                .param("sn", sellerName()).param("sg", sellerGstin()).param("sa", sellerAddress()).param("ss", sellerState())
                .param("bn", profile.get("legal_name")).param("ba", address).param("bs", buyerState).param("bg", profile.get("gstin"))
                .param("be", profile.get("email")).param("biz", profile.get("name")).param("sac", properties.sac)
                .param("tx", taxes.taxable()).param("c", taxes.cgst()).param("s", taxes.sgst()).param("i", taxes.igst()).param("tot", taxes.total())
                .param("cr", taxes.cgstRate()).param("sr", taxes.sgstRate()).param("ir", taxes.igstRate()).query(Long.class).single();
        jdbc.sql("""
                        insert into sf_invoice_lines (customer_tenant_id, invoice_id, line_no, description, plan_code, period, period_start, period_end, taxable_amount)
                        values (:t, :i, 1, :d, :p, :per, :ps, :pe, :tx)""")
                .param("t", tenantId).param("i", id).param("d", line.description()).param("p", line.planCode()).param("per", line.period())
                .param("ps", Date.valueOf(line.periodStart())).param("pe", Date.valueOf(line.periodEnd())).param("tx", taxes.taxable()).update();
        return invoice(tenantId, id);
    }

    /**
     * The taxes of a GST-inclusive total: the taxable amount is worked back from the total, each tax is worked out on
     * it, and the taxable amount takes the rounding, so the invoice adds up to exactly what was paid.
     */
    static Gst.Taxes fromTotal(BigDecimal total, String sellerState, String buyerState, Gst.Rates rates) {
        BigDecimal rate = sellerState.equals(buyerState) ? rates.cgst().add(rates.sgst()) : rates.igst();
        BigDecimal guess = total.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(100).add(rate), 2, RoundingMode.HALF_UP);
        Gst.Taxes t = Gst.taxes(guess, sellerState, buyerState, rates);
        BigDecimal taxable = Gst.money(total).subtract(t.tax());
        return new Gst.Taxes(taxable, t.cgstRate(), t.cgst(), t.sgstRate(), t.sgst(), t.igstRate(), t.igst());
    }

    /** Issues a credit note for a refund of part or all of an invoice. */
    @Transactional
    public CreditNote creditNote(long invoiceId, BigDecimal amount, String reason, String gatewayRefundId, Long adminId, LocalDate today) {
        Invoice inv = invoiceAnyTenant(invoiceId);
        Gst.Taxes original = new Gst.Taxes(inv.taxableAmount(), inv.cgstRate(), inv.cgst(), inv.sgstRate(), inv.sgst(), inv.igstRate(), inv.igst());
        Gst.Taxes share = Gst.share(original, amount);
        String fy = Gst.financialYear(today);
        String number = Gst.number(properties.invoicePrefix + "CN", fy, next(CREDIT_NOTE_SERIES, fy));
        long tenantId = jdbc.sql("select customer_tenant_id from sf_invoices where id = :i").param("i", invoiceId).query(Long.class).single();
        long id = jdbc.sql("""
                        insert into sf_credit_notes (customer_tenant_id, number, financial_year, note_date, invoice_id, reason, taxable_amount, cgst, sgst, igst,
                          total, gateway_refund_id, issued_by) values (:t, :n, :fy, :d, :i, :r, :tx, :c, :s, :ig, :tot, :g, :u) returning id""")
                .param("t", tenantId).param("n", number).param("fy", fy).param("d", Date.valueOf(today)).param("i", invoiceId).param("r", reason)
                .param("tx", share.taxable()).param("c", share.cgst()).param("s", share.sgst()).param("ig", share.igst()).param("tot", share.total())
                .param("g", gatewayRefundId).param("u", adminId).query(Long.class).single();
        return creditNotes(invoiceId).stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    private long next(String series, String financialYear) {
        return jdbc.sql("""
                        insert into sf_document_counters (series, financial_year, next_number) values (:s, :fy, 2)
                        on conflict (series, financial_year) do update set next_number = sf_document_counters.next_number + 1
                        returning next_number - 1""").param("s", series).param("fy", financialYear).query(Long.class).single();
    }

    // ---- Reading -------------------------------------------------------------------------------------------------------

    private static final String COLUMNS = """
            select i.id, i.number, i.invoice_date, i.issued_at, i.business_name, i.buyer_name, i.buyer_address, i.buyer_gstin, i.buyer_state_code,
                   i.seller_name, i.seller_gstin, i.seller_address, i.seller_state_code, i.sac, l.description, l.plan_code, l.period, l.period_start, l.period_end,
                   i.taxable_amount, i.cgst_rate, i.cgst, i.sgst_rate, i.sgst, i.igst_rate, i.igst, i.total, p.refunded_amount, i.booked_document_id
            from sf_invoices i join sf_invoice_lines l on l.invoice_id = i.id and l.line_no = 1 join sf_payments p on p.id = i.payment_id""";

    private Invoice row(ResultSet rs) throws SQLException {
        long id = rs.getLong(1);
        return new Invoice(id, rs.getString(2), rs.getDate(3).toLocalDate(), rs.getTimestamp(4).toInstant(), rs.getString(5), rs.getString(6), rs.getString(7),
                rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14), rs.getString(15),
                rs.getString(16), rs.getString(17), rs.getDate(18).toLocalDate(), rs.getDate(19).toLocalDate(), rs.getBigDecimal(20), rs.getBigDecimal(21),
                rs.getBigDecimal(22), rs.getBigDecimal(23), rs.getBigDecimal(24), rs.getBigDecimal(25), rs.getBigDecimal(26), rs.getBigDecimal(27),
                rs.getBigDecimal(28), (Long) rs.getObject(29), creditNotes(id));
    }

    public List<CreditNote> creditNotes(long invoiceId) {
        return jdbc.sql("select id, number, note_date, reason, taxable_amount, cgst, sgst, igst, total from sf_credit_notes where invoice_id = :i order by id")
                .param("i", invoiceId).query((rs, i) -> new CreditNote(rs.getLong(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getString(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8), rs.getBigDecimal(9))).list();
    }

    @Transactional(readOnly = true)
    public List<Invoice> list(long tenantId) {
        return jdbc.sql(COLUMNS + " where i.customer_tenant_id = :t order by i.issued_at desc, i.id desc").param("t", tenantId).query((rs, i) -> row(rs)).list();
    }

    @Transactional(readOnly = true)
    public Invoice invoice(long tenantId, long id) {
        return jdbc.sql(COLUMNS + " where i.id = :i and i.customer_tenant_id = :t").param("i", id).param("t", tenantId).query((rs, i) -> row(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "Invoice not found."));
    }

    Invoice invoiceAnyTenant(long id) {
        return jdbc.sql(COLUMNS + " where i.id = :i").param("i", id).query((rs, i) -> row(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("INVOICE_NOT_FOUND", "Invoice not found."));
    }

    public record InvoiceRow(long id, long tenantId, String number, LocalDate invoiceDate, String businessName, BigDecimal total, BigDecimal refundedAmount) {}

    /** The platform console's list: the newest invoices of every farm business. */
    @Transactional(readOnly = true)
    public List<InvoiceRow> recent(int limit) {
        return jdbc.sql("""
                        select i.id, i.customer_tenant_id, i.number, i.invoice_date, i.business_name, i.total, p.refunded_amount
                        from sf_invoices i join sf_payments p on p.id = i.payment_id order by i.issued_at desc, i.id desc limit :l""").param("l", limit)
                .query((rs, i) -> new InvoiceRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDate(4).toLocalDate(), rs.getString(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7))).list();
    }

    /** Records that the owner booked the invoice as a supplier bill in the farm's books (P13); once only. */
    @Transactional
    public void markBooked(long tenantId, long invoiceId, long documentId) {
        int n = jdbc.sql("update sf_invoices set booked_document_id = :d, booked_at = :at where id = :i and customer_tenant_id = :t and booked_document_id is null")
                .param("d", documentId).param("at", Timestamp.from(Instant.now())).param("i", invoiceId).param("t", tenantId).update();
        if (n == 0) throw ApiException.conflict("INVOICE_ALREADY_BOOKED", "This invoice is already in your books.");
    }

    // ---- The seller (from configuration; development falls back to placeholders that production refuses) -------------

    String sellerName() { return properties.sellerName.isBlank() ? "SmartFarm360 (development)" : properties.sellerName; }

    String sellerGstin() { return properties.sellerGstin.isBlank() ? "NOT-SET" : properties.sellerGstin; }

    String sellerAddress() { return properties.sellerAddress.isBlank() ? "Not set" : properties.sellerAddress; }

    String sellerState() { return properties.sellerState.isBlank() ? "33" : properties.sellerState; }
}
