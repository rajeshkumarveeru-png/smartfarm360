package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

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

    public FinanceController(DocumentService documents, MasterDataService master, ReportService reports, FinanceSettingsService settings,
                             AllocationService allocations) {
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
                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return documents.list(ctx, type, status, partyId, from, to, page, size);
    }

    @GetMapping("/documents/{documentId}")
    @RequiresPermission(Permission.FINANCE_READ)
    public Document document(TenantContext ctx, @PathVariable long documentId) { return documents.document(ctx, documentId); }

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

    @GetMapping("/reports/income-statement")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.IncomeStatement incomeStatement(TenantContext ctx,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long farmId, @RequestParam(required = false) Long costObjectId,
            @RequestParam(defaultValue = "ACCRUAL") String basis) {
        return reports.incomeStatement(ctx, from, to, farmId, costObjectId, basis);
    }

    @GetMapping("/reports/cash-flow")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.CashFlow cashFlow(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.cashFlow(ctx, from, to);
    }

    @GetMapping("/reports/dues")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Dues dues(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.dues(ctx, asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/reports/balances")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Balances balances(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.balances(ctx, asOf == null ? LocalDate.now() : asOf);
    }

    @GetMapping("/reports/activity")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Activity activity(TenantContext ctx, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.activity(ctx, from, to);
    }

    @GetMapping("/reports/summary")
    @RequiresPermission(Permission.FINANCE_READ)
    public ReportService.Summary summary(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.summary(ctx, asOf == null ? LocalDate.now() : asOf);
    }
}
