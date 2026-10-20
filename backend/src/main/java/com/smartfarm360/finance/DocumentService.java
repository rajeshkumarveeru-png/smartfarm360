package com.smartfarm360.finance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.FinanceDtos.*;
import com.smartfarm360.finance.PostingEngine.Line;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;

/**
 * Creates, posts, settles and reverses finance records (posting rules, sections 4–7).
 * Every method runs in one transaction: a record, its journal, its allocations and its audit entry are written
 * together or not at all.
 */
@Service
public class DocumentService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final JdbcClient jdbc;
    private final PostingEngine posting;
    private final MasterDataService master;
    private final FinanceSettingsService settings;
    private final CostObjectService costObjects;
    private final AuditService audit;
    private final ObjectMapper json;

    public DocumentService(JdbcClient jdbc, PostingEngine posting, MasterDataService master, FinanceSettingsService settings,
                           CostObjectService costObjects, AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.posting = posting;
        this.master = master;
        this.settings = settings;
        this.costObjects = costObjects;
        this.audit = audit;
        this.json = json;
    }

    /** Header fields of a new record. */
    private record Header(DocType type, LocalDate docDate, LocalDate dueDate, Long partyId, Long farmId, Long financialAccountId,
                          Long toFinancialAccountId, Long loanId, BigDecimal total, BigDecimal principal, BigDecimal interest,
                          String notes, String referenceNo) {
        Header(DocType type, LocalDate docDate, Long partyId, Long financialAccountId, BigDecimal total, String notes) {
            this(type, docDate, null, partyId, null, financialAccountId, null, null, total, null, null, notes, null);
        }
    }

    private record NewLine(Long categoryId, String accountCode, String side, String description, BigDecimal quantity, String unit,
                           BigDecimal rate, BigDecimal amount, Long costObjectId, Long farmId, Long partyId, Long financialAccountId, Long loanId) {}

    // ---- Bills and sales -----------------------------------------------------------------------------------------

    @Transactional
    public Document createInvoice(TenantContext ctx, DocType type, InvoiceRequest r, String idempotencyKey) {
        Optional<Document> replay = replay(ctx, idempotencyKey, type, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        boolean post = r.post() == null || r.post();
        List<NewLine> lines = invoiceLines(ctx, type, r);
        BigDecimal total = lines.stream().map(NewLine::amount).reduce(ZERO, BigDecimal::add);
        validateInvoiceHeader(ctx, r, total, post);

        Header h = new Header(type, r.docDate(), r.dueDate(), r.partyId(), r.farmId(), r.partyId() == null ? r.financialAccountId() : null,
                null, null, total, null, null, trim(r.notes()), trim(r.referenceNo()));
        long id = insert(ctx, h, lines, post ? "POSTED" : "DRAFT", idempotencyKey, hash(type, r));
        if (post) {
            postInternal(ctx, id);
            if (r.paidNow() != null) {
                DocType settlingType = type == DocType.BILL ? DocType.PAYMENT : DocType.RECEIPT;
                long paymentId = insert(ctx, new Header(settlingType, r.docDate(), r.partyId(), r.paidNow().financialAccountId(),
                        money(r.paidNow().amount()), null), List.of(), "POSTED", null, null);
                postInternal(ctx, paymentId);
                allocate(ctx, paymentId, id, money(r.paidNow().amount()));
            }
        } else {
            audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_DRAFT_SAVED", "FINANCE_DOCUMENT", id, Map.of("type", type.name(), "total", total));
        }
        return document(ctx, id);
    }

    /** Replaces a draft bill or sale. */
    @Transactional
    public Document updateDraft(TenantContext ctx, long id, InvoiceRequest r) {
        DocRow d = lockDocument(ctx, id);
        if (!"DRAFT".equals(d.status())) throw ApiException.conflict("NOT_A_DRAFT", "Only drafts can be edited.");
        if (r.paidNow() != null) throw ApiException.badRequest("PAID_NOW_NOT_ALLOWED", "Record the payment after posting.");
        List<NewLine> lines = invoiceLines(ctx, d.type(), r);
        BigDecimal total = lines.stream().map(NewLine::amount).reduce(ZERO, BigDecimal::add);
        validateInvoiceHeader(ctx, r, total, false);
        jdbc.sql("delete from sf_finance_document_lines where document_id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        jdbc.sql("""
                        update sf_finance_documents set doc_date = :date, due_date = :due, party_id = :party, farm_id = :farm,
                          financial_account_id = :fin, total_amount = :total, notes = :notes, reference_no = :ref, updated_at = now()
                        where id = :id and tenant_id = :t""")
                .param("date", r.docDate()).param("due", r.dueDate()).param("party", r.partyId()).param("farm", r.farmId())
                .param("fin", r.partyId() == null ? r.financialAccountId() : null).param("total", total).param("notes", trim(r.notes()))
                .param("ref", trim(r.referenceNo())).param("id", id).param("t", ctx.tenantId()).update();
        insertLines(ctx, id, lines);
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_DRAFT_SAVED", "FINANCE_DOCUMENT", id, Map.of("type", d.type().name(), "total", total));
        return document(ctx, id);
    }

    @Transactional
    public Document postDraft(TenantContext ctx, long id) {
        DocRow d = lockDocument(ctx, id);
        if (!"DRAFT".equals(d.status())) throw ApiException.conflict("NOT_A_DRAFT", "Only drafts can be posted.");
        settings.requireOpen(ctx.tenantId(), d.docDate());
        if (d.partyId() == null && d.financialAccountId() == null) {
            throw ApiException.badRequest("PARTY_OR_ACCOUNT_REQUIRED", "Choose a party, or the cash/bank account it was paid from.");
        }
        postInternal(ctx, id);
        return document(ctx, id);
    }

    @Transactional
    public Document cancelDraft(TenantContext ctx, long id) {
        DocRow d = lockDocument(ctx, id);
        if (!"DRAFT".equals(d.status())) throw ApiException.conflict("NOT_A_DRAFT", "Only drafts can be cancelled; reverse posted records.");
        jdbc.sql("update sf_finance_documents set status = 'CANCELLED', updated_at = now() where id = :id and tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_DRAFT_CANCELLED", "FINANCE_DOCUMENT", id, Map.of("type", d.type().name()));
        return document(ctx, id);
    }

    private List<NewLine> invoiceLines(TenantContext ctx, DocType type, InvoiceRequest r) {
        List<NewLine> lines = new ArrayList<>();
        for (LineRequest l : r.lines()) {
            Category c = master.category(ctx, l.categoryId());
            if (!c.active()) throw ApiException.badRequest("CATEGORY_INACTIVE", "This category is no longer in use.");
            boolean allowed = type == DocType.BILL ? !"INCOME".equals(c.kind()) : "INCOME".equals(c.kind());
            if (!allowed) throw ApiException.badRequest("CATEGORY_MISMATCH", "This category cannot be used on this record.");
            BigDecimal amount = lineAmount(l);
            if (l.costObjectId() != null) master.requireCostObject(ctx.tenantId(), l.costObjectId());
            if (l.farmId() != null) master.requireFarm(ctx.tenantId(), l.farmId());
            lines.add(new NewLine(c.id(), c.accountCode(), null, trim(l.description()), l.quantity(), trim(l.unit()), l.rate(), amount,
                    l.costObjectId(), l.farmId(), null, null, null));
        }
        return lines;
    }

    /** amount, or quantity × rate rounded half-up to the paisa (posting rules, section 7). */
    static BigDecimal lineAmount(LineRequest l) {
        BigDecimal computed = l.quantity() != null && l.rate() != null ? l.quantity().multiply(l.rate()).setScale(2, RoundingMode.HALF_UP) : null;
        if (l.amount() != null && computed != null && l.amount().setScale(2, RoundingMode.HALF_UP).compareTo(computed) != 0) {
            throw ApiException.badRequest("AMOUNT_MISMATCH", "Amount does not equal quantity × rate.");
        }
        BigDecimal amount = l.amount() != null ? money(l.amount()) : computed;
        if (amount == null || amount.signum() <= 0) throw ApiException.badRequest("AMOUNT_REQUIRED", "Enter an amount, or a quantity and rate.");
        return amount;
    }

    private void validateInvoiceHeader(TenantContext ctx, InvoiceRequest r, BigDecimal total, boolean post) {
        if (r.dueDate() != null && r.dueDate().isBefore(r.docDate())) throw ApiException.badRequest("INVALID_DUE_DATE", "Due date cannot be before the record date.");
        if (r.farmId() != null) master.requireFarm(ctx.tenantId(), r.farmId());
        if (r.partyId() != null) {
            master.requireParty(ctx.tenantId(), r.partyId());
        } else {
            if (r.paidNow() != null) throw ApiException.badRequest("PARTY_REQUIRED", "Choose a party to record a part payment.");
            if (r.financialAccountId() != null) master.requireFinancialAccount(ctx.tenantId(), r.financialAccountId(), true);
            else if (post) throw ApiException.badRequest("PARTY_OR_ACCOUNT_REQUIRED", "Choose a party, or the cash/bank account it was paid from.");
        }
        if (r.paidNow() != null) {
            if (!post) throw ApiException.badRequest("PAID_NOW_NOT_ALLOWED", "Record the payment after posting.");
            master.requireFinancialAccount(ctx.tenantId(), r.paidNow().financialAccountId(), true);
            if (money(r.paidNow().amount()).compareTo(total) > 0) throw ApiException.badRequest("OVERPAID", "Paid amount is more than the total.");
        }
        if (post) settings.requireOpen(ctx.tenantId(), r.docDate());
    }

    // ---- Payments, receipts and other money movements ------------------------------------------------------------

    @Transactional
    public Document createPayment(TenantContext ctx, DocType type, PaymentRequest r, String idempotencyKey) {
        Optional<Document> replay = replay(ctx, idempotencyKey, type, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        master.requireParty(ctx.tenantId(), r.partyId());
        master.requireFinancialAccount(ctx.tenantId(), r.financialAccountId(), true);
        settings.requireOpen(ctx.tenantId(), r.docDate());
        BigDecimal amount = money(r.amount());
        List<AllocationRequest> allocations = r.allocations() == null ? List.of() : r.allocations();
        BigDecimal allocated = allocations.stream().map(a -> money(a.amount())).reduce(ZERO, BigDecimal::add);
        if (allocated.compareTo(amount) > 0) throw ApiException.badRequest("OVER_ALLOCATED", "Allocations are more than the amount.");

        long id = insert(ctx, new Header(type, r.docDate(), null, r.partyId(), null, r.financialAccountId(), null, null, amount, null, null,
                trim(r.notes()), trim(r.referenceNo())), List.of(), "POSTED", idempotencyKey, hash(type, r));
        postInternal(ctx, id);
        for (AllocationRequest a : allocations) allocate(ctx, id, a.documentId(), money(a.amount()));
        return document(ctx, id);
    }

    @Transactional
    public Document createTransfer(TenantContext ctx, TransferRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.TRANSFER, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        if (r.fromAccountId().equals(r.toAccountId())) throw ApiException.badRequest("SAME_ACCOUNT", "Choose two different accounts.");
        master.requireFinancialAccount(ctx.tenantId(), r.fromAccountId(), true);
        master.requireFinancialAccount(ctx.tenantId(), r.toAccountId(), true);
        settings.requireOpen(ctx.tenantId(), r.docDate());
        long id = insert(ctx, new Header(DocType.TRANSFER, r.docDate(), null, null, null, r.fromAccountId(), r.toAccountId(), null,
                money(r.amount()), null, null, trim(r.notes()), null), List.of(), "POSTED", key, hash(DocType.TRANSFER, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    @Transactional
    public Document createOwnerMoney(TenantContext ctx, OwnerMoneyRequest r, String key) {
        DocType type = "IN".equals(r.direction()) ? DocType.OWNER_IN : DocType.OWNER_OUT;
        Optional<Document> replay = replay(ctx, key, type, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        master.requireFinancialAccount(ctx.tenantId(), r.financialAccountId(), true);
        settings.requireOpen(ctx.tenantId(), r.docDate());
        long id = insert(ctx, new Header(type, r.docDate(), null, r.financialAccountId(), money(r.amount()), trim(r.notes())),
                List.of(), "POSTED", key, hash(type, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    @Transactional
    public Document createLoan(TenantContext ctx, LoanRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.LOAN_RECEIVED, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        master.requireParty(ctx.tenantId(), r.lenderPartyId());
        master.requireFinancialAccount(ctx.tenantId(), r.financialAccountId(), true);
        settings.requireOpen(ctx.tenantId(), r.docDate());
        long loanId = newLoan(ctx.tenantId(), r.lenderPartyId(), r.loanName(), r.docDate(), r.notes());
        long id = insert(ctx, new Header(DocType.LOAN_RECEIVED, r.docDate(), null, r.lenderPartyId(), null, r.financialAccountId(), null,
                loanId, money(r.amount()), null, null, trim(r.notes()), null), List.of(), "POSTED", key, hash(DocType.LOAN_RECEIVED, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    @Transactional
    public Document createLoanRepayment(TenantContext ctx, LoanRepaymentRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.LOAN_REPAYMENT, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        long lender = jdbc.sql("select lender_party_id from sf_loans where id = :id and tenant_id = :t for update")
                .param("id", r.loanId()).param("t", ctx.tenantId()).query(Long.class).optional()
                .orElseThrow(() -> ApiException.notFound("LOAN_NOT_FOUND", "Loan not found."));
        master.requireFinancialAccount(ctx.tenantId(), r.financialAccountId(), true);
        settings.requireOpen(ctx.tenantId(), r.docDate());
        BigDecimal principal = money(r.principal());
        BigDecimal interest = money(r.interest());
        BigDecimal total = principal.add(interest);
        if (total.signum() <= 0) throw ApiException.badRequest("AMOUNT_REQUIRED", "Enter the principal and/or interest paid.");
        if (principal.compareTo(master.loanOutstanding(ctx.tenantId(), r.loanId())) > 0) {
            throw ApiException.badRequest("PRINCIPAL_EXCEEDS_BALANCE", "Principal is more than the loan balance.");
        }
        long id = insert(ctx, new Header(DocType.LOAN_REPAYMENT, r.docDate(), null, lender, null, r.financialAccountId(), null, r.loanId(),
                total, principal, interest, trim(r.notes()), null), List.of(), "POSTED", key, hash(DocType.LOAN_REPAYMENT, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    /** Nets what a party owes us against what we owe them (P12), allocating to their oldest open bills and sales. */
    @Transactional
    public Document createSettlement(TenantContext ctx, SettlementRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.SETTLEMENT, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        lockParty(ctx, r.partyId());
        settings.requireOpen(ctx.tenantId(), r.docDate());
        BigDecimal amount = money(r.amount());
        Party party = master.party(ctx, r.partyId());
        if (amount.compareTo(party.receivable()) > 0 || amount.compareTo(party.payable()) > 0) {
            throw ApiException.badRequest("SETTLEMENT_TOO_LARGE", "Settlement cannot be more than what either side owes.");
        }
        long id = insert(ctx, new Header(DocType.SETTLEMENT, r.docDate(), r.partyId(), null, amount, trim(r.notes())),
                List.of(), "POSTED", key, hash(DocType.SETTLEMENT, r));
        postInternal(ctx, id);
        allocateOldestFirst(ctx, id, r.partyId(), DocType.BILL, amount);
        allocateOldestFirst(ctx, id, r.partyId(), DocType.SALE, amount);
        return document(ctx, id);
    }

    @Transactional
    public Document createWriteOff(TenantContext ctx, WriteOffRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.WRITE_OFF, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        lockParty(ctx, r.partyId());
        settings.requireOpen(ctx.tenantId(), r.docDate());
        BigDecimal amount = money(r.amount());
        if (amount.compareTo(master.party(ctx, r.partyId()).receivable()) > 0) {
            throw ApiException.badRequest("WRITE_OFF_TOO_LARGE", "Write-off cannot be more than the party owes.");
        }
        long id = insert(ctx, new Header(DocType.WRITE_OFF, r.docDate(), r.partyId(), null, amount, trim(r.notes())),
                List.of(), "POSTED", key, hash(DocType.WRITE_OFF, r));
        postInternal(ctx, id);
        allocateOldestFirst(ctx, id, r.partyId(), DocType.SALE, amount);
        return document(ctx, id);
    }

    @Transactional
    public Document createOpeningBalance(TenantContext ctx, OpeningBalanceRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.OPENING_BALANCE, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        settings.requireOpen(ctx.tenantId(), r.docDate());
        List<NewLine> lines = new ArrayList<>();
        for (OpeningLine l : r.lines()) {
            BigDecimal amount = money(l.amount());
            switch (l.kind()) {
                case "CASH" -> {
                    if (l.financialAccountId() == null) throw ApiException.badRequest("FINANCIAL_ACCOUNT_REQUIRED", "Choose the cash or bank account.");
                    String code = master.requireFinancialAccount(ctx.tenantId(), l.financialAccountId(), false);
                    lines.add(new NewLine(null, code, "DEBIT", trim(l.description()), null, null, null, amount, null, null, null, l.financialAccountId(), null));
                }
                case "RECEIVABLE", "PAYABLE" -> {
                    if (l.partyId() == null) throw ApiException.badRequest("PARTY_REQUIRED", "Choose the party.");
                    master.requireParty(ctx.tenantId(), l.partyId());
                    boolean receivable = "RECEIVABLE".equals(l.kind());
                    lines.add(new NewLine(null, receivable ? LedgerAccount.RECEIVABLES.code() : LedgerAccount.PAYABLES.code(),
                            receivable ? "DEBIT" : "CREDIT", trim(l.description()), null, null, null, amount, null, null, l.partyId(), null, null));
                }
                case "LOAN" -> {
                    if (l.partyId() == null || l.loanName() == null || l.loanName().isBlank()) {
                        throw ApiException.badRequest("LOAN_DETAILS_REQUIRED", "Choose the lender and name the loan.");
                    }
                    master.requireParty(ctx.tenantId(), l.partyId());
                    long loanId = newLoan(ctx.tenantId(), l.partyId(), l.loanName(), r.docDate(), null);
                    lines.add(new NewLine(null, LedgerAccount.LOANS.code(), "CREDIT", trim(l.description()), null, null, null, amount, null, null, l.partyId(), null, loanId));
                }
                default -> lines.add(new NewLine(null, LedgerAccount.FARM_ASSETS.code(), "DEBIT", trim(l.description()), null, null, null, amount, null, null, null, null, null));
            }
        }
        BigDecimal total = lines.stream().map(NewLine::amount).reduce(ZERO, BigDecimal::add);
        long id = insert(ctx, new Header(DocType.OPENING_BALANCE, r.docDate(), null, null, total, trim(r.notes())), lines, "POSTED", key,
                hash(DocType.OPENING_BALANCE, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    @Transactional
    public Document createDepreciation(TenantContext ctx, DepreciationRequest r, String key) {
        Optional<Document> replay = replay(ctx, key, DocType.DEPRECIATION, r);
        if (replay.isPresent()) return replay.get();
        settings.ensureDefaults(ctx.tenantId());
        if (r.costObjectId() != null) master.requireCostObject(ctx.tenantId(), r.costObjectId());
        settings.requireOpen(ctx.tenantId(), r.docDate());
        BigDecimal amount = money(r.amount());
        long id = insert(ctx, new Header(DocType.DEPRECIATION, r.docDate(), null, null, amount, trim(r.notes())),
                List.of(new NewLine(null, LedgerAccount.DEPRECIATION.code(), "DEBIT", null, null, null, null, amount, r.costObjectId(), null, null, null, null)),
                "POSTED", key, hash(DocType.DEPRECIATION, r));
        postInternal(ctx, id);
        return document(ctx, id);
    }

    /**
     * Posts a stock purchase from the Phase 1 purchase screen as a bill to Stock (1200) — not an expense until the
     * stock is used (ADR 0005) — plus a payment for the part paid now. Replaced by Phase 4 stock documents.
     */
    @Transactional
    public long recordStockPurchase(TenantContext ctx, long partyId, LocalDate date, String description, BigDecimal quantity, String unit,
                                    BigDecimal rate, BigDecimal total, BigDecimal paid, Long financialAccountId) {
        settings.ensureDefaults(ctx.tenantId());
        settings.requireOpen(ctx.tenantId(), date);
        BigDecimal amount = money(total);
        if (amount.signum() <= 0) return 0;
        long id = insert(ctx, new Header(DocType.BILL, date, partyId, null, amount, null),
                List.of(new NewLine(null, LedgerAccount.STOCK.code(), null, description, quantity, unit, rate, amount, null, null, null, null, null)),
                "POSTED", null, null);
        postInternal(ctx, id);
        if (paid != null && paid.signum() > 0) {
            long account = financialAccountId != null ? financialAccountId : defaultCashAccount(ctx.tenantId());
            master.requireFinancialAccount(ctx.tenantId(), account, true);
            long paymentId = insert(ctx, new Header(DocType.PAYMENT, date, partyId, account, money(paid), null), List.of(), "POSTED", null, null);
            postInternal(ctx, paymentId);
            allocate(ctx, paymentId, id, money(paid));
        }
        return id;
    }

    private long defaultCashAccount(long tenantId) {
        return jdbc.sql("select id from sf_financial_accounts where tenant_id = :t and active and kind = 'CASH' order by id limit 1")
                .param("t", tenantId).query(Long.class).optional()
                .orElseThrow(() -> ApiException.badRequest("FINANCIAL_ACCOUNT_REQUIRED", "Choose the cash or bank account it was paid from."));
    }

    // ---- Allocations ---------------------------------------------------------------------------------------------

    @Transactional
    public Document addAllocation(TenantContext ctx, long sourceId, AllocationRequest r) {
        DocRow source = lockDocument(ctx, sourceId);
        if (!"POSTED".equals(source.status()) || !source.type().allocates()) {
            throw ApiException.conflict("CANNOT_ALLOCATE", "Only posted payments, receipts, settlements and write-offs can be matched.");
        }
        BigDecimal amount = money(r.amount());
        BigDecimal used = allocatedFrom(sourceId, null);
        if (source.type() != DocType.SETTLEMENT && used.add(amount).compareTo(source.total()) > 0) {
            throw ApiException.badRequest("OVER_ALLOCATED", "This would match more than the record's amount.");
        }
        allocate(ctx, sourceId, r.documentId(), amount);
        return document(ctx, sourceId);
    }

    @Transactional
    public void removeAllocation(TenantContext ctx, long allocationId) {
        Map<String, Object> row = jdbc.sql("""
                        select a.id, a.allocation_date, a.source_document_id from sf_payment_allocations a
                        where a.id = :id and a.tenant_id = :t and a.removed_at is null for update""")
                .param("id", allocationId).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ALLOCATION_NOT_FOUND", "Match not found."));
        settings.requireOpen(ctx.tenantId(), ((java.sql.Date) row.get("allocation_date")).toLocalDate());
        jdbc.sql("update sf_payment_allocations set removed_at = now(), removed_by = :u where id = :id")
                .param("u", ctx.userId()).param("id", allocationId).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_ALLOCATION_REMOVED", "FINANCE_ALLOCATION", allocationId,
                Map.of("sourceDocumentId", row.get("source_document_id")));
    }

    /** Matches part of a settling record to an open bill or sale of the same party. */
    private void allocate(TenantContext ctx, long sourceId, long targetId, BigDecimal amount) {
        DocRow source = loadDocument(ctx, sourceId);
        DocRow target = lockDocument(ctx, targetId);
        if (!"POSTED".equals(target.status())) throw ApiException.conflict("TARGET_NOT_OPEN", "Only posted bills and sales can be matched.");
        if (!source.type().canSettle(target.type())) throw ApiException.badRequest("ALLOCATION_TYPE_MISMATCH", "This record cannot settle that one.");
        if (!Objects.equals(source.partyId(), target.partyId())) throw ApiException.badRequest("ALLOCATION_PARTY_MISMATCH", "Both records must be for the same party.");
        BigDecimal open = target.total().subtract(allocatedTo(targetId));
        if (amount.compareTo(open) > 0) throw ApiException.badRequest("OVER_ALLOCATED", "This is more than the open amount of " + open + ".");
        if (source.type() == DocType.SETTLEMENT && allocatedFrom(sourceId, target.type()).add(amount).compareTo(source.total()) > 0) {
            throw ApiException.badRequest("OVER_ALLOCATED", "This would match more than the settlement amount.");
        }
        LocalDate date = source.docDate().isAfter(target.docDate()) ? source.docDate() : target.docDate();
        settings.requireOpen(ctx.tenantId(), date);
        long id = jdbc.sql("""
                        insert into sf_payment_allocations (tenant_id, source_document_id, target_document_id, amount, allocation_date, created_by)
                        values (:t, :s, :d, :a, :date, :u) returning id""")
                .param("t", ctx.tenantId()).param("s", sourceId).param("d", targetId).param("a", amount).param("date", date).param("u", ctx.userId())
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_ALLOCATION_ADDED", "FINANCE_ALLOCATION", id,
                Map.of("sourceDocumentId", sourceId, "targetDocumentId", targetId, "amount", amount));
    }

    private void allocateOldestFirst(TenantContext ctx, long sourceId, long partyId, DocType targetType, BigDecimal amount) {
        BigDecimal remaining = amount;
        List<Map<String, Object>> open = jdbc.sql("""
                        select d.id, d.total_amount - coalesce((select sum(a.amount) from sf_payment_allocations a
                                  where a.target_document_id = d.id and a.removed_at is null), 0) as open
                        from sf_finance_documents d where d.tenant_id = :t and d.party_id = :p and d.doc_type = :type and d.status = 'POSTED'
                        order by d.doc_date, d.id""")
                .param("t", ctx.tenantId()).param("p", partyId).param("type", targetType.name()).query().listOfRows();
        for (Map<String, Object> row : open) {
            if (remaining.signum() <= 0) break;
            BigDecimal openAmount = (BigDecimal) row.get("open");
            if (openAmount.signum() <= 0) continue;
            BigDecimal part = openAmount.min(remaining);
            allocate(ctx, sourceId, ((Number) row.get("id")).longValue(), part);
            remaining = remaining.subtract(part);
        }
    }

    private BigDecimal allocatedTo(long targetId) {
        return jdbc.sql("select coalesce(sum(amount), 0) from sf_payment_allocations where target_document_id = :d and removed_at is null")
                .param("d", targetId).query(BigDecimal.class).single();
    }

    private BigDecimal allocatedFrom(long sourceId, DocType targetType) {
        return jdbc.sql("""
                        select coalesce(sum(a.amount), 0) from sf_payment_allocations a join sf_finance_documents t on t.id = a.target_document_id
                        where a.source_document_id = :s and a.removed_at is null and (cast(:type as varchar) is null or t.doc_type = :type)""")
                .param("s", sourceId).param("type", targetType == null ? null : targetType.name()).query(BigDecimal.class).single();
    }

    // ---- Reversal ------------------------------------------------------------------------------------------------

    /** Cancels a posted record's effect with a mirror journal entry (P18). Posted records are never edited. */
    @Transactional
    public Document reverse(TenantContext ctx, long id, ReverseRequest r) {
        DocRow d = lockDocument(ctx, id);
        if (!"POSTED".equals(d.status())) throw ApiException.conflict("NOT_POSTED", "Only posted records can be reversed.");
        LocalDate lock = settings.settings(ctx.tenantId()).lockDate();
        LocalDate date = r == null || r.reversalDate() == null
                ? (lock == null || d.docDate().isAfter(lock) ? d.docDate() : LocalDate.now())
                : r.reversalDate();
        if (date.isBefore(d.docDate())) throw ApiException.badRequest("INVALID_REVERSAL_DATE", "Reversal date cannot be before the record date.");
        settings.requireOpen(ctx.tenantId(), date);
        if (d.type().isInvoice() && allocatedTo(id).signum() > 0) {
            throw ApiException.conflict("HAS_ALLOCATIONS", "Payments are matched to this record. Reverse or unmatch them first.");
        }
        if (d.type() == DocType.LOAN_RECEIVED && jdbc.sql("""
                        select exists (select 1 from sf_finance_documents where tenant_id = :t and loan_id = :l and doc_type = 'LOAN_REPAYMENT' and status = 'POSTED')""")
                .param("t", ctx.tenantId()).param("l", d.loanId()).query(Boolean.class).single()) {
            throw ApiException.conflict("LOAN_HAS_REPAYMENTS", "Reverse the repayments of this loan first.");
        }
        if (d.type().allocates()) {
            jdbc.sql("update sf_payment_allocations set removed_at = now(), removed_by = :u where source_document_id = :id and removed_at is null")
                    .param("u", ctx.userId()).param("id", id).update();
        }
        posting.reverse(ctx.tenantId(), id, date);
        jdbc.sql("update sf_finance_documents set status = 'REVERSED', reversal_date = :date, reversed_at = now(), reversed_by = :u, updated_at = now() where id = :id")
                .param("date", date).param("u", ctx.userId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_DOCUMENT_REVERSED", "FINANCE_DOCUMENT", id,
                Map.of("type", d.type().name(), "number", d.number(), "reversalDate", date.toString()));
        return document(ctx, id);
    }

    // ---- Reading -------------------------------------------------------------------------------------------------

    private static final String SUMMARY_SELECT = """
            select d.id, d.doc_type, d.status, d.number, d.doc_date, d.due_date, d.party_id, p.name as party_name, f.name as fin_name,
                   d.total_amount,
                   coalesce(case when d.doc_type in ('BILL', 'SALE')
                                 then (select sum(a.amount) from sf_payment_allocations a where a.target_document_id = d.id and a.removed_at is null)
                                 else (select sum(a.amount) from sf_payment_allocations a where a.source_document_id = d.id and a.removed_at is null) end, 0) as settled,
                   d.needs_review, d.created_at
            from sf_finance_documents d
            left join sf_parties p on p.id = d.party_id and p.tenant_id = d.tenant_id
            left join sf_financial_accounts f on f.id = d.financial_account_id and f.tenant_id = d.tenant_id""";

    @Transactional(readOnly = true)
    public PageResponse<DocumentSummary> list(TenantContext ctx, String type, String status, Long partyId, LocalDate from, LocalDate to,
                                              int page, int size) {
        PageResponse.check(page, size);
        String where = """
                 where d.tenant_id = :t and (cast(:type as varchar) is null or d.doc_type = :type)
                   and (cast(:status as varchar) is null or d.status = :status) and (cast(:party as bigint) is null or d.party_id = :party)
                   and (cast(:from as date) is null or d.doc_date >= :from) and (cast(:to as date) is null or d.doc_date <= :to)""";
        Map<String, Object> params = new HashMap<>();
        params.put("t", ctx.tenantId());
        params.put("type", type);
        params.put("status", status);
        params.put("party", partyId);
        params.put("from", from);
        params.put("to", to);
        long total = jdbc.sql("select count(*) from sf_finance_documents d" + where).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        List<DocumentSummary> items = jdbc.sql(SUMMARY_SELECT + where + " order by d.doc_date desc, d.id desc limit :limit offset :offset")
                .params(params).query((rs, i) -> summary(rs)).list();
        return PageResponse.of(items, page, size, total);
    }

    @Transactional(readOnly = true)
    public Document document(TenantContext ctx, long id) {
        DocumentSummary summary = jdbc.sql(SUMMARY_SELECT + " where d.id = :id and d.tenant_id = :t").param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> summary(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Record not found."));
        Map<String, Object> extra = jdbc.sql("""
                        select d.farm_id, d.financial_account_id, d.to_financial_account_id, tf.name as to_name, d.loan_id, l.name as loan_name,
                               d.principal_amount, d.interest_amount, d.notes, d.reference_no, d.reversal_date, d.posted_at
                        from sf_finance_documents d
                        left join sf_financial_accounts tf on tf.id = d.to_financial_account_id and tf.tenant_id = d.tenant_id
                        left join sf_loans l on l.id = d.loan_id and l.tenant_id = d.tenant_id
                        where d.id = :id and d.tenant_id = :t""")
                .param("id", id).param("t", ctx.tenantId()).query().singleRow();
        List<DocumentLine> lines = jdbc.sql("""
                        select l.id, l.line_no, l.category_id, c.code, c.name, l.account_code, l.side, l.description, l.quantity, l.unit, l.rate,
                               l.amount, l.cost_object_id, co.name, l.farm_id, l.party_id, l.financial_account_id, l.loan_id
                        from sf_finance_document_lines l
                        left join sf_finance_categories c on c.id = l.category_id
                        left join sf_cost_objects co on co.id = l.cost_object_id and co.tenant_id = l.tenant_id
                        where l.document_id = :id and l.tenant_id = :t order by l.line_no""")
                .param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> new DocumentLine(rs.getLong(1), rs.getInt(2), (Long) rs.getObject(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getString(8), rs.getBigDecimal(9), rs.getString(10), rs.getBigDecimal(11),
                        rs.getBigDecimal(12), (Long) rs.getObject(13), rs.getString(14), (Long) rs.getObject(15), (Long) rs.getObject(16),
                        (Long) rs.getObject(17), (Long) rs.getObject(18)))
                .list();
        List<Allocation> allocations = jdbc.sql("""
                        select a.id, a.source_document_id, s.number, s.doc_type, a.target_document_id, t.number, t.doc_type, a.amount, a.allocation_date
                        from sf_payment_allocations a
                        join sf_finance_documents s on s.id = a.source_document_id join sf_finance_documents t on t.id = a.target_document_id
                        where a.tenant_id = :t and a.removed_at is null and (a.source_document_id = :id or a.target_document_id = :id)
                        order by a.allocation_date, a.id""")
                .param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> new Allocation(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6),
                        rs.getString(7), rs.getBigDecimal(8), rs.getObject(9, LocalDate.class)))
                .list();
        return new Document(summary, (Long) extra.get("farm_id"), (Long) extra.get("financial_account_id"),
                (Long) extra.get("to_financial_account_id"), (String) extra.get("to_name"), (Long) extra.get("loan_id"),
                (String) extra.get("loan_name"), (BigDecimal) extra.get("principal_amount"), (BigDecimal) extra.get("interest_amount"),
                (String) extra.get("notes"), (String) extra.get("reference_no"),
                extra.get("reversal_date") == null ? null : ((java.sql.Date) extra.get("reversal_date")).toLocalDate(),
                extra.get("posted_at") == null ? null : ((java.sql.Timestamp) extra.get("posted_at")).toInstant(), lines, allocations);
    }

    private static DocumentSummary summary(ResultSet rs) throws SQLException {
        DocType type = DocType.valueOf(rs.getString("doc_type"));
        BigDecimal total = rs.getBigDecimal("total_amount");
        BigDecimal settled = rs.getBigDecimal("settled");
        if (type == DocType.SETTLEMENT) settled = settled.min(total);
        BigDecimal open = total.subtract(settled);
        String status = rs.getString("status");
        String paymentStatus = !type.isInvoice() || !"POSTED".equals(status) ? null
                : rs.getObject("party_id") == null || open.signum() == 0 ? "PAID" : settled.signum() == 0 ? "UNPAID" : "PARTLY_PAID";
        if (type.isInvoice() && rs.getObject("party_id") == null) { settled = total; open = BigDecimal.ZERO.setScale(2); }
        return new DocumentSummary(rs.getLong("id"), type.name(), status, rs.getString("number"), rs.getObject("doc_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), (Long) rs.getObject("party_id"), rs.getString("party_name"), rs.getString("fin_name"),
                total, settled, open, paymentStatus, rs.getBoolean("needs_review"), rs.getTimestamp("created_at").toInstant());
    }

    // ---- Internals -----------------------------------------------------------------------------------------------

    record DocRow(long id, DocType type, String status, String number, LocalDate docDate, Long partyId, Long financialAccountId,
                  Long loanId, BigDecimal total) {}

    private DocRow lockDocument(TenantContext ctx, long id) { return documentRow(ctx, id, true); }

    private DocRow loadDocument(TenantContext ctx, long id) { return documentRow(ctx, id, false); }

    private DocRow documentRow(TenantContext ctx, long id, boolean lock) {
        return jdbc.sql("select id, doc_type, status, number, doc_date, party_id, financial_account_id, loan_id, total_amount " +
                        "from sf_finance_documents where id = :id and tenant_id = :t" + (lock ? " for update" : ""))
                .param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> new DocRow(rs.getLong(1), DocType.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class), (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), rs.getBigDecimal(9)))
                .optional().orElseThrow(() -> ApiException.notFound("DOCUMENT_NOT_FOUND", "Record not found."));
    }

    /** Serializes concurrent settlements and write-offs for the same party so balance checks stay valid. */
    private void lockParty(TenantContext ctx, long partyId) {
        jdbc.sql("select id from sf_parties where id = :id and tenant_id = :t for update").param("id", partyId).param("t", ctx.tenantId())
                .query(Long.class).optional().orElseThrow(() -> ApiException.notFound("PARTY_NOT_FOUND", "Party not found."));
    }

    /** Records to be posted are inserted as drafts; {@link #postInternal} numbers them and marks them posted. */
    private long insert(TenantContext ctx, Header h, List<NewLine> lines, String status, String key, String requestHash) {
        String initialStatus = "POSTED".equals(status) ? "DRAFT" : status;
        long id = jdbc.sql("""
                        insert into sf_finance_documents (tenant_id, doc_type, status, doc_date, due_date, party_id, farm_id, financial_account_id,
                          to_financial_account_id, loan_id, total_amount, principal_amount, interest_amount, notes, reference_no,
                          idempotency_key, request_hash, created_by)
                        values (:t, :type, :status, :date, :due, :party, :farm, :fin, :toFin, :loan, :total, :principal, :interest, :notes, :ref,
                          :key, :hash, :u) returning id""")
                .param("t", ctx.tenantId()).param("type", h.type().name()).param("status", initialStatus).param("date", h.docDate())
                .param("due", h.dueDate()).param("party", h.partyId()).param("farm", h.farmId()).param("fin", h.financialAccountId())
                .param("toFin", h.toFinancialAccountId()).param("loan", h.loanId()).param("total", h.total()).param("principal", h.principal())
                .param("interest", h.interest()).param("notes", h.notes()).param("ref", h.referenceNo()).param("key", key)
                .param("hash", requestHash).param("u", ctx.userId())
                .query(Long.class).single();
        insertLines(ctx, id, lines);
        return id;
    }

    private void insertLines(TenantContext ctx, long documentId, List<NewLine> lines) {
        int no = 1;
        for (NewLine l : lines) {
            jdbc.sql("""
                            insert into sf_finance_document_lines (tenant_id, document_id, line_no, category_id, account_code, side, description,
                              quantity, unit, rate, amount, cost_object_id, farm_id, party_id, financial_account_id, loan_id)
                            values (:t, :d, :no, :cat, :acc, :side, :desc, :qty, :unit, :rate, :amount, :co, :farm, :party, :fin, :loan)""")
                    .param("t", ctx.tenantId()).param("d", documentId).param("no", no++).param("cat", l.categoryId()).param("acc", l.accountCode())
                    .param("side", l.side()).param("desc", l.description()).param("qty", l.quantity()).param("unit", l.unit()).param("rate", l.rate())
                    .param("amount", l.amount()).param("co", l.costObjectId()).param("farm", l.farmId()).param("party", l.partyId())
                    .param("fin", l.financialAccountId()).param("loan", l.loanId())
                    .update();
        }
    }

    /** Numbers a posted record, writes its journal and audits it. */
    private void postInternal(TenantContext ctx, long id) {
        Map<String, Object> d = jdbc.sql("""
                        select doc_type, doc_date, party_id, farm_id, financial_account_id, to_financial_account_id, loan_id, total_amount,
                               coalesce(principal_amount, 0) as principal, coalesce(interest_amount, 0) as interest
                        from sf_finance_documents where id = :id and tenant_id = :t""")
                .param("id", id).param("t", ctx.tenantId()).query().singleRow();
        DocType type = DocType.valueOf((String) d.get("doc_type"));
        LocalDate date = ((java.sql.Date) d.get("doc_date")).toLocalDate();
        String number = nextNumber(ctx.tenantId(), type, date);
        List<Line> lines = jdbc.sql("""
                        select category_id, account_code, side, amount, cost_object_id, farm_id, party_id, financial_account_id, loan_id
                        from sf_finance_document_lines where document_id = :id and tenant_id = :t order by line_no""")
                .param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> new Line((Long) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), (Long) rs.getObject(5),
                        (Long) rs.getObject(6), (Long) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9)))
                .list();
        PostingEngine.Doc doc = new PostingEngine.Doc(id, ctx.tenantId(), type, date, (Long) d.get("party_id"), (Long) d.get("farm_id"),
                (Long) d.get("financial_account_id"), (Long) d.get("to_financial_account_id"), (Long) d.get("loan_id"),
                (BigDecimal) d.get("total_amount"), (BigDecimal) d.get("principal"), (BigDecimal) d.get("interest"));
        posting.write(ctx.tenantId(), id, date, "POSTING", posting.journal(doc, lines, costObjects.overhead(ctx.tenantId())));
        jdbc.sql("update sf_finance_documents set status = 'POSTED', number = :n, posted_at = now(), posted_by = :u, updated_at = now() where id = :id")
                .param("n", number).param("u", ctx.userId()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_DOCUMENT_POSTED", "FINANCE_DOCUMENT", id,
                Map.of("type", type.name(), "number", number, "total", d.get("total_amount")));
    }

    private String nextNumber(long tenantId, DocType type, LocalDate date) {
        int startMonth = settings.settings(tenantId).fiscalYearStartMonth();
        int fiscalYear = FinanceSettingsService.fiscalYearStart(date, startMonth).getYear();
        int value = jdbc.sql("""
                        insert into sf_document_sequences (tenant_id, doc_type, fiscal_year, next_value) values (:t, :type, :fy, 2)
                        on conflict (tenant_id, doc_type, fiscal_year) do update set next_value = sf_document_sequences.next_value + 1
                        returning next_value - 1""")
                .param("t", tenantId).param("type", type.name()).param("fy", fiscalYear).query(Integer.class).single();
        return type.prefix() + "/" + FinanceSettingsService.fiscalYearLabel(date, startMonth) + "/" + String.format("%04d", value);
    }

    private long newLoan(long tenantId, long lenderPartyId, String name, LocalDate start, String notes) {
        jdbc.sql("update sf_parties set is_lender = true where id = :p and tenant_id = :t").param("p", lenderPartyId).param("t", tenantId).update();
        return jdbc.sql("insert into sf_loans (tenant_id, lender_party_id, name, start_date, notes) values (:t, :p, :n, :d, :notes) returning id")
                .param("t", tenantId).param("p", lenderPartyId).param("n", name.trim()).param("d", start).param("notes", trim(notes))
                .query(Long.class).single();
    }

    /**
     * Idempotent retries: the same key with the same request returns the original record; a different request with a
     * reused key is refused, so a double tap or a mobile retry never records money twice.
     */
    private Optional<Document> replay(TenantContext ctx, String key, DocType type, Object request) {
        if (key == null || key.isBlank()) return Optional.empty();
        if (key.length() > 100) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is too long.");
        return jdbc.sql("select id, request_hash from sf_finance_documents where tenant_id = :t and idempotency_key = :k")
                .param("t", ctx.tenantId()).param("k", key)
                .query((rs, i) -> Map.entry(rs.getLong(1), Objects.toString(rs.getString(2), "")))
                .optional()
                .map(existing -> {
                    if (!existing.getValue().equals(hash(type, request))) {
                        throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This request key was already used for a different record.");
                    }
                    return document(ctx, existing.getKey());
                });
    }

    private String hash(DocType type, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((type.name() + ":" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot hash request", e);
        }
    }

    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP); }

    private static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
