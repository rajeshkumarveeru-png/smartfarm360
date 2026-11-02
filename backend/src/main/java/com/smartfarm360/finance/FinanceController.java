package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.*;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportDocument;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * Finance API (posting rules, approved 2026-10-20). Money-creating requests accept an {@code Idempotency-Key}
 * header so retries never record anything twice.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/finance")
public class FinanceController {
    private static final String KEY = "Idempotency-Key";
    private final DocumentService documents;
    private final MasterDataService master;
    private final ReportService reports;
    private final FinanceSettingsService settings;
    private final AllocationService allocations;
    private final LedgerReportService ledger;
    private final FinanceExports exports;
    private final ExportService export;
    private final SubscriptionBookingService subscriptionBooking;

    public FinanceController(DocumentService documents, MasterDataService master, ReportService reports, FinanceSettingsService settings,
                             AllocationService allocations, LedgerReportService ledger, FinanceExports exports, ExportService export,
                             SubscriptionBookingService subscriptionBooking) {
        this.subscriptionBooking = subscriptionBooking;
        this.ledger = ledger;
        this.exports = exports;
        this.export = export;
        this.documents = documents;
        this.master = master;
        this.reports = reports;
        this.settings = settings;
        this.allocations = allocations;
    }

    // ---- Settings and master data ----

    @GetMapping("/settings")
    @RequiresPermission(Permission.FINANCE_READ)
    public FinanceSettings settings(TenantContext ctx) { return settings.settings(ctx.tenantId()); }

    @PutMapping("/settings")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public FinanceSettings updateSettings(TenantContext ctx, @Valid @RequestBody FinanceSettingsRequest r) { return settings.update(ctx, r); }

    @GetMapping("/categories")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<Category> categories(TenantContext ctx) { return master.categories(ctx); }

    @PostMapping("/categories")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Category createCategory(TenantContext ctx, @Valid @RequestBody CategoryRequest r) { return master.createCategory(ctx, r); }

    @PatchMapping("/categories/{categoryId}")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public Category updateCategory(TenantContext ctx, @PathVariable long categoryId, @Valid @RequestBody CategoryPatch r) {
        return master.updateCategory(ctx, categoryId, r);
    }

    @GetMapping("/accounts")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<FinancialAccount> accounts(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return master.financialAccounts(ctx, asOf == null ? LocalDate.now() : asOf);
    }

    @PostMapping("/accounts")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public FinancialAccount createAccount(TenantContext ctx, @Valid @RequestBody FinancialAccountRequest r) { return master.createFinancialAccount(ctx, r); }

    @PatchMapping("/accounts/{accountId}")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public FinancialAccount updateAccount(TenantContext ctx, @PathVariable long accountId, @Valid @RequestBody FinancialAccountPatch r) {
        return master.updateFinancialAccount(ctx, accountId, r);
    }

    @GetMapping("/parties")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<Party> parties(TenantContext ctx) { return master.parties(ctx); }

    @GetMapping("/parties/{partyId}")
    @RequiresPermission(Permission.FINANCE_READ)
    public Party party(TenantContext ctx, @PathVariable long partyId) { return master.party(ctx, partyId); }

    @PostMapping("/parties")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Party createParty(TenantContext ctx, @Valid @RequestBody PartyRequest r) { return master.createParty(ctx, r); }

    @PatchMapping("/parties/{partyId}")
    @RequiresPermission(Permission.FINANCE_WRITE)
    public Party updateParty(TenantContext ctx, @PathVariable long partyId, @Valid @RequestBody PartyPatch r) { return master.updateParty(ctx, partyId, r); }

    @GetMapping("/loans")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<Loan> loans(TenantContext ctx) { return master.loans(ctx); }

    @GetMapping("/cost-objects")
    @RequiresPermission(Permission.FINANCE_READ)
    public List<CostObject> costObjects(TenantContext ctx) { return master.costObjects(ctx); }

    // ---- Records ----

    @GetMapping("/documents")
    @RequiresPermission(Permission.FINANCE_READ)
    public PageResponse<DocumentSummary> list(TenantContext ctx, @RequestParam(required = false) String type,
                                              @RequestParam(required = false) String status, @RequestParam(required = false) Long partyId,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) Long farmId, @RequestParam(required = false) Long costObjectId,
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return documents.list(ctx, type, status, partyId, farmId, costObjectId, from, to, page, size);
    }

    @GetMapping("/documents/{documentId}")
    @RequiresPermission(Permission.FINANCE_READ)
    public Document document(TenantContext ctx, @PathVariable long documentId) { return documents.document(ctx, documentId); }

    public record BookingRequest(Long financialAccountId) {}

    /** Records a paid SmartFarm360 invoice as a supplier bill in these books, when an owner asks (billing design, P13). */
    @PostMapping("/subscription-invoices/{invoiceId}/book")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document bookSubscriptionInvoice(TenantContext ctx, @PathVariable long invoiceId, @RequestBody(required = false) BookingRequest r) {
        return subscriptionBooking.book(ctx, invoiceId, r == null ? null : r.financialAccountId());
    }

    @PostMapping("/bills")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document bill(TenantContext ctx, @Valid @RequestBody InvoiceRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createInvoice(ctx, DocType.BILL, r, key);
    }

    @PostMapping("/sales")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document sale(TenantContext ctx, @Valid @RequestBody InvoiceRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createInvoice(ctx, DocType.SALE, r, key);
    }

    @PutMapping("/documents/{documentId}")
    @RequiresPermission(Permission.FINANCE_WRITE)
    public Document updateDraft(TenantContext ctx, @PathVariable long documentId, @Valid @RequestBody InvoiceRequest r) {
        return documents.updateDraft(ctx, documentId, r);
    }

    @PostMapping("/documents/{documentId}/post")
    @RequiresPermission(Permission.FINANCE_WRITE)
    public Document post(TenantContext ctx, @PathVariable long documentId) { return documents.postDraft(ctx, documentId); }

    @PostMapping("/documents/{documentId}/cancel")
    @RequiresPermission(Permission.FINANCE_WRITE)
    public Document cancel(TenantContext ctx, @PathVariable long documentId) { return documents.cancelDraft(ctx, documentId); }

    @PostMapping("/documents/{documentId}/reverse")
    @RequiresPermission(Permission.FINANCE_REVERSE)
    public Document reverse(TenantContext ctx, @PathVariable long documentId, @Valid @RequestBody(required = false) ReverseRequest r) {
        return documents.reverse(ctx, documentId, r);
    }

    @PostMapping("/documents/{documentId}/allocations")
    @RequiresPermission(Permission.FINANCE_WRITE)
    public Document allocate(TenantContext ctx, @PathVariable long documentId, @Valid @RequestBody AllocationRequest r) {
        return documents.addAllocation(ctx, documentId, r);
    }

    @DeleteMapping("/allocations/{allocationId}")
    @RequiresPermission(Permission.FINANCE_REVERSE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeAllocation(TenantContext ctx, @PathVariable long allocationId) { documents.removeAllocation(ctx, allocationId); }

    @PostMapping("/payments")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document payment(TenantContext ctx, @Valid @RequestBody PaymentRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createPayment(ctx, DocType.PAYMENT, r, key);
    }

    @PostMapping("/receipts")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document receipt(TenantContext ctx, @Valid @RequestBody PaymentRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createPayment(ctx, DocType.RECEIPT, r, key);
    }

    @PostMapping("/transfers")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document transfer(TenantContext ctx, @Valid @RequestBody TransferRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createTransfer(ctx, r, key);
    }

    @PostMapping("/owner-money")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document ownerMoney(TenantContext ctx, @Valid @RequestBody OwnerMoneyRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createOwnerMoney(ctx, r, key);
    }

    @PostMapping("/loans")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document loan(TenantContext ctx, @Valid @RequestBody LoanRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createLoan(ctx, r, key);
    }

    @PostMapping("/loan-repayments")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document loanRepayment(TenantContext ctx, @Valid @RequestBody LoanRepaymentRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createLoanRepayment(ctx, r, key);
    }

    @PostMapping("/settlements")
    @RequiresPermission(Permission.FINANCE_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document settlement(TenantContext ctx, @Valid @RequestBody SettlementRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createSettlement(ctx, r, key);
    }

    @PostMapping("/write-offs")
    @RequiresPermission(Permission.FINANCE_REVERSE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document writeOff(TenantContext ctx, @Valid @RequestBody WriteOffRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createWriteOff(ctx, r, key);
    }

    @PostMapping("/opening-balances")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document openingBalance(TenantContext ctx, @Valid @RequestBody OpeningBalanceRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createOpeningBalance(ctx, r, key);
    }

    @PostMapping("/depreciation")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document depreciation(TenantContext ctx, @Valid @RequestBody DepreciationRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return documents.createDepreciation(ctx, r, key);
    }

    /** Shows how farm overhead of a period would be spread to the crops growing in it, without posting anything. */
    @PostMapping("/overhead-allocations/preview")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public AllocationPreview allocationPreview(TenantContext ctx, @Valid @RequestBody OverheadAllocationRequest r) {
        return allocations.preview(ctx, r.from(), r.to());
    }

    @PostMapping("/overhead-allocations")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    @ResponseStatus(HttpStatus.CREATED)
    public Document allocate(TenantContext ctx, @Valid @RequestBody OverheadAllocationRequest r, @RequestHeader(name = KEY, required = false) String key) {
        return allocations.allocate(ctx, r, key);
    }

    // ---- Reports ----
    // Each report answers JSON, or a file when format=csv|xlsx (dashboard and reports design, H7); lang labels the file.

    @GetMapping("/reports/income-statement")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> incomeStatement(TenantContext ctx,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long farmId, @RequestParam(required = false) Long costObjectId,
            @RequestParam(defaultValue = "ACCRUAL") String basis, @RequestParam(required = false) String compare,
            @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        if (compare != null && !compare.isBlank()) {
            LedgerReportService.IncomeComparison c = ledger.compareIncome(ctx, from, to, farmId, costObjectId, basis, compare);
            return respond(ctx, c, format, () -> exports.comparison(lang(lang), ctx.tenantId(), c), lang, "income-statement", from, to);
        }
        ReportService.IncomeStatement s = reports.incomeStatement(ctx, from, to, farmId, costObjectId, basis);
        return respond(ctx, s, format, () -> exports.income(lang(lang), ctx.tenantId(), s), lang, "income-statement", from, to);
    }

    @GetMapping("/reports/monthly")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> monthly(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) Long farmId, @RequestParam(defaultValue = "ACCRUAL") String basis,
                                     @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LedgerReportService.MonthlyProfitAndLoss m = ledger.monthly(ctx, from, to, farmId, basis);
        return respond(ctx, m, format, () -> exports.monthly(lang(lang), ctx.tenantId(), m), lang, "monthly-profit-and-loss", from, to);
    }

    @GetMapping("/reports/categories")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> categories(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(defaultValue = "COSTS") String kind, @RequestParam(required = false) Long farmId,
                                        @RequestParam(required = false) Long costObjectId, @RequestParam(defaultValue = "ACCRUAL") String basis,
                                        @RequestParam(required = false) String compare,
                                        @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LedgerReportService.CategoryBreakdown c = ledger.categories(ctx, from, to, kind, farmId, costObjectId, basis, compare);
        return respond(ctx, c, format, () -> exports.categories(lang(lang), ctx.tenantId(), c), lang, "categories", from, to);
    }

    @GetMapping("/reports/balance-sheet")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> balanceSheet(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                          @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        LedgerReportService.BalanceSheet b = ledger.balanceSheet(ctx, date);
        return respond(ctx, b, format, () -> exports.balanceSheet(lang(lang), b), lang, "balance-sheet", date, date);
    }

    @GetMapping("/reports/trial-balance")
    @RequiresPermission(Permission.FINANCE_MANAGE)
    public ResponseEntity<?> trialBalance(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                          @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LedgerReportService.TrialBalance t = ledger.trialBalance(ctx, from, to);
        return respond(ctx, t, format, () -> exports.trialBalance(lang(lang), t), lang, "trial-balance", from, to);
    }

    @GetMapping("/reports/cash-flow")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> cashFlow(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        ReportService.CashFlow c = reports.cashFlow(ctx, from, to);
        return respond(ctx, c, format, () -> exports.cashFlow(lang(lang), c), lang, "cash-flow", from, to);
    }

    @GetMapping("/reports/dues")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> dues(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
                                  @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LocalDate date = asOf == null ? LocalDate.now() : asOf;
        ReportService.Dues d = reports.dues(ctx, date);
        return respond(ctx, d, format, () -> exports.dues(lang(lang), d), lang, "dues", date, date);
    }

    @GetMapping("/reports/balances")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Balances balances(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.balances(ctx, asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/reports/activity")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> activity(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        ReportService.Activity a = reports.activity(ctx, from, to);
        return respond(ctx, a, format, () -> exports.activity(lang(lang), a), lang, "activity", from, to);
    }

    @GetMapping("/reports/summary")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Summary summary(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.summary(ctx, asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/parties/{partyId}/statement")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> partyStatement(TenantContext ctx, @PathVariable long partyId,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LedgerReportService.PartyStatement s = ledger.partyStatement(ctx, partyId, from, to);
        return respond(ctx, s, format, () -> exports.partyStatement(lang(lang), s), lang, "party-statement-" + partyId, from, to);
    }

    private static String lang(String lang) { return ReportLabels.language(lang); }

    private ResponseEntity<?> respond(TenantContext ctx, Object json, String format, Supplier<ReportDocument> doc, String lang, String code,
                                      LocalDate from, LocalDate to) {
        if (!ExportService.isExport(format)) return ResponseEntity.ok(json);
        return export.export(ctx, doc.get(), format, lang, code, from, to);
    }
}
