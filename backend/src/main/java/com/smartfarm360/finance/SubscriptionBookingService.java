package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.finance.FinanceDtos.PaidNow;
import com.smartfarm360.finance.FinanceDtos.PartyRequest;
import com.smartfarm360.platform.billing.InvoiceService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records a paid SmartFarm360 invoice in the farm's own books, when an owner asks (billing design, P13): a posted
 * supplier bill from "SmartFarm360" under Software and subscriptions, for the invoice's total including GST, and
 * the payment from the chosen cash or bank account if one is given. Never automatic; each invoice once only.
 */
@Service
public class SubscriptionBookingService {
    static final String SUPPLIER = "SmartFarm360";

    private final InvoiceService invoices;
    private final DocumentService documents;
    private final MasterDataService master;
    private final JdbcClient jdbc;

    public SubscriptionBookingService(InvoiceService invoices, DocumentService documents, MasterDataService master, JdbcClient jdbc) {
        this.invoices = invoices;
        this.documents = documents;
        this.master = master;
        this.jdbc = jdbc;
    }

    @Transactional
    public Document book(TenantContext ctx, long invoiceId, Long financialAccountId) {
        if (!ctx.has(Permission.BILLING_VIEW)) throw ApiException.forbidden("ACCESS_DENIED", "You do not have permission to do this.");
        InvoiceService.Invoice inv = invoices.invoice(ctx.tenantId(), invoiceId);
        if (inv.bookedDocumentId() != null) throw ApiException.conflict("INVOICE_ALREADY_BOOKED", "This invoice is already in your books.");
        long supplier = master.parties(ctx).stream().filter(p -> p.supplier() && SUPPLIER.equals(p.name())).map(FinanceDtos.Party::id).findFirst()
                .orElseGet(() -> master.createParty(ctx, new PartyRequest(SUPPLIER, null, null, "Software subscription", false, true, false)).id());
        long category = jdbc.sql("select id from sf_finance_categories where code = 'SOFTWARE' and tenant_id is null").query(Long.class).single();
        String gst = inv.cgst().add(inv.sgst()).add(inv.igst()).toPlainString();
        Document bill = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(inv.invoiceDate(), inv.invoiceDate(), supplier, null, null,
                List.of(new LineRequest(category, inv.description(), null, null, null, inv.total(), null, null)),
                "SmartFarm360 invoice " + inv.number() + "; includes GST " + gst, inv.number(), true,
                financialAccountId == null ? null : new PaidNow(financialAccountId, inv.total())), "billing-invoice-" + invoiceId);
        invoices.markBooked(ctx.tenantId(), invoiceId, bill.summary().id());
        return bill;
    }
}
