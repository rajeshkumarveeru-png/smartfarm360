package com.smartfarm360.finance;

import com.smartfarm360.finance.FinanceDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Categories, cash/bank accounts, parties, loans and cost objects. Every query is scoped to the tenant. */
@Service
public class MasterDataService {
    private final JdbcClient jdbc;
    private final FinanceSettingsService settings;
    private final AuditService audit;

    public MasterDataService(JdbcClient jdbc, FinanceSettingsService settings, AuditService audit) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.audit = audit;
    }

    // ---- Categories ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Category> categories(TenantContext ctx) {
        return jdbc.sql("""
                        select id, code, name, kind, account_code, tenant_id is null as system, active from sf_finance_categories
                        where tenant_id is null or tenant_id = :t order by tenant_id nulls first, kind, id""")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6), rs.getBoolean(7)))
                .list();
    }

    @Transactional
    public Category createCategory(TenantContext ctx, CategoryRequest request) {
        LedgerAccount account = LedgerAccount.find(request.accountCode())
                .orElseThrow(() -> ApiException.badRequest("INVALID_ACCOUNT", "Unknown account."));
        boolean matches = switch (request.kind()) {
            case "EXPENSE" -> account.type() == LedgerAccount.Type.EXPENSE;
            case "INCOME" -> account.type() == LedgerAccount.Type.INCOME;
            default -> account == LedgerAccount.FARM_ASSETS;
        };
        if (!matches) throw ApiException.badRequest("INVALID_ACCOUNT", "The account does not match the category kind.");
        long id = jdbc.sql("insert into sf_finance_categories (tenant_id, name, kind, account_code) values (:t, :name, :kind, :code) returning id")
                .param("t", ctx.tenantId()).param("name", request.name().trim()).param("kind", request.kind()).param("code", account.code())
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_CATEGORY_CREATED", "FINANCE_CATEGORY", id, Map.of("name", request.name().trim()));
        return category(ctx, id);
    }

    @Transactional
    public Category updateCategory(TenantContext ctx, long id, CategoryPatch patch) {
        Category existing = category(ctx, id);
        if (existing.system()) throw ApiException.forbidden("SYSTEM_CATEGORY", "System categories cannot be changed.");
        if (patch.name() != null) jdbc.sql("update sf_finance_categories set name = :n where id = :id").param("n", patch.name().trim()).param("id", id).update();
        if (patch.active() != null) jdbc.sql("update sf_finance_categories set active = :a where id = :id").param("a", patch.active()).param("id", id).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_CATEGORY_UPDATED", "FINANCE_CATEGORY", id, Map.of());
        return category(ctx, id);
    }

    public Category category(TenantContext ctx, long id) {
        return jdbc.sql("""
                        select id, code, name, kind, account_code, tenant_id is null, active from sf_finance_categories
                        where id = :id and (tenant_id is null or tenant_id = :t)""")
                .param("id", id).param("t", ctx.tenantId())
                .query((rs, i) -> new Category(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getBoolean(6), rs.getBoolean(7)))
                .optional().orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found."));
    }

    // ---- Cash, bank and UPI accounts ----------------------------------------------------------------------------

    @Transactional
    public List<FinancialAccount> financialAccounts(TenantContext ctx, LocalDate asOf) {
        settings.ensureDefaults(ctx.tenantId());
        return jdbc.sql("""
                        select f.id, f.name, f.kind, f.active,
                               coalesce((select sum(l.debit - l.credit) from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                                         where l.tenant_id = f.tenant_id and l.financial_account_id = f.id and e.entry_date <= :asOf), 0)
                        from sf_financial_accounts f where f.tenant_id = :t order by f.active desc, f.kind, f.name""")
                .param("t", ctx.tenantId()).param("asOf", asOf)
                .query((rs, i) -> new FinancialAccount(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), rs.getBigDecimal(5)))
                .list();
    }

    @Transactional
    public FinancialAccount createFinancialAccount(TenantContext ctx, FinancialAccountRequest request) {
        String code = "CASH".equals(request.kind()) ? LedgerAccount.CASH.code() : LedgerAccount.BANK.code();
        if (jdbc.sql("select exists (select 1 from sf_financial_accounts where tenant_id = :t and lower(name) = lower(:n))")
                .param("t", ctx.tenantId()).param("n", request.name().trim()).query(Boolean.class).single()) {
            throw ApiException.conflict("DUPLICATE_NAME", "An account with this name already exists.");
        }
        long id = jdbc.sql("insert into sf_financial_accounts (tenant_id, name, kind, account_code) values (:t, :n, :k, :c) returning id")
                .param("t", ctx.tenantId()).param("n", request.name().trim()).param("k", request.kind()).param("c", code)
                .query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCIAL_ACCOUNT_CREATED", "FINANCIAL_ACCOUNT", id, Map.of("name", request.name().trim()));
        return financialAccounts(ctx, LocalDate.now()).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public FinancialAccount updateFinancialAccount(TenantContext ctx, long id, FinancialAccountPatch patch) {
        requireFinancialAccount(ctx.tenantId(), id, false);
        if (patch.name() != null) jdbc.sql("update sf_financial_accounts set name = :n, updated_at = now() where id = :id and tenant_id = :t")
                .param("n", patch.name().trim()).param("id", id).param("t", ctx.tenantId()).update();
        if (patch.active() != null) jdbc.sql("update sf_financial_accounts set active = :a, updated_at = now() where id = :id and tenant_id = :t")
                .param("a", patch.active()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCIAL_ACCOUNT_UPDATED", "FINANCIAL_ACCOUNT", id, Map.of());
        return financialAccounts(ctx, LocalDate.now()).stream().filter(a -> a.id() == id).findFirst().orElseThrow();
    }

    /** Returns the ledger code (1000 or 1010) of a tenant's cash/bank account. */
    public String requireFinancialAccount(long tenantId, long id, boolean mustBeActive) {
        return jdbc.sql("select account_code from sf_financial_accounts where id = :id and tenant_id = :t" + (mustBeActive ? " and active" : ""))
                .param("id", id).param("t", tenantId).query(String.class).optional()
                .orElseThrow(() -> ApiException.notFound("FINANCIAL_ACCOUNT_NOT_FOUND", "Cash or bank account not found."));
    }

    // ---- Parties ------------------------------------------------------------------------------------------------

    private static final String PARTY_SELECT = """
            select p.id, p.name, p.phone, p.village, p.notes, p.is_customer, p.is_supplier, p.is_lender, p.active, p.is_worker,
                   coalesce((select sum(l.debit - l.credit) from sf_journal_lines l where l.tenant_id = p.tenant_id and l.party_id = p.id and l.account_code = '1100'), 0),
                   coalesce((select sum(l.credit - l.debit) from sf_journal_lines l where l.tenant_id = p.tenant_id and l.party_id = p.id and l.account_code = '2000'), 0)
            from sf_parties p""";

    @Transactional(readOnly = true)
    public List<Party> parties(TenantContext ctx) {
        return jdbc.sql(PARTY_SELECT + " where p.tenant_id = :t order by p.active desc, lower(p.name)")
                .param("t", ctx.tenantId()).query((rs, i) -> party(rs)).list();
    }

    @Transactional(readOnly = true)
    public Party party(TenantContext ctx, long id) {
        return jdbc.sql(PARTY_SELECT + " where p.tenant_id = :t and p.id = :id")
                .param("t", ctx.tenantId()).param("id", id).query((rs, i) -> party(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("PARTY_NOT_FOUND", "Party not found."));
    }

    @Transactional
    public Party createParty(TenantContext ctx, PartyRequest r) {
        long id = insertParty(ctx.tenantId(), r.name(), r.phone(), r.village(), r.notes(), r.customer(), r.supplier(), r.lender(), null);
        audit.record(ctx.tenantId(), ctx.userId(), "PARTY_CREATED", "PARTY", id, Map.of("name", r.name().trim()));
        return party(ctx, id);
    }

    private long insertParty(long tenantId, String name, String phone, String village, String notes, boolean customer, boolean supplier,
                             boolean lender, Long legacySupplierId) {
        return jdbc.sql("""
                        insert into sf_parties (tenant_id, name, phone, village, notes, is_customer, is_supplier, is_lender, legacy_supplier_id)
                        values (:t, :name, :phone, :village, :notes, :c, :s, :l, :legacy) returning id""")
                .param("t", tenantId).param("name", name.trim()).param("phone", blankToNull(phone)).param("village", blankToNull(village))
                .param("notes", blankToNull(notes)).param("c", customer).param("s", supplier).param("l", lender).param("legacy", legacySupplierId)
                .query(Long.class).single();
    }

    @Transactional
    public Party updateParty(TenantContext ctx, long id, PartyPatch p) {
        party(ctx, id);
        jdbc.sql("""
                        update sf_parties set name = coalesce(:name, name), phone = coalesce(:phone, phone), village = coalesce(:village, village),
                          notes = coalesce(:notes, notes), is_customer = coalesce(:c, is_customer), is_supplier = coalesce(:s, is_supplier),
                          is_lender = coalesce(:l, is_lender), active = coalesce(:a, active), updated_at = now()
                        where id = :id and tenant_id = :t""")
                .param("name", p.name() == null ? null : p.name().trim()).param("phone", p.phone()).param("village", p.village())
                .param("notes", p.notes()).param("c", p.customer()).param("s", p.supplier()).param("l", p.lender()).param("a", p.active())
                .param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "PARTY_UPDATED", "PARTY", id, Map.of());
        return party(ctx, id);
    }

    public void requireParty(long tenantId, long id) {
        if (!jdbc.sql("select exists (select 1 from sf_parties where id = :id and tenant_id = :t)").param("id", id).param("t", tenantId)
                .query(Boolean.class).single()) {
            throw ApiException.notFound("PARTY_NOT_FOUND", "Party not found.");
        }
    }

    private static Party party(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Party(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6),
                rs.getBoolean(7), rs.getBoolean(8), rs.getBoolean(9), rs.getBigDecimal(11), rs.getBigDecimal(12), rs.getBoolean(10));
    }

    // ---- Loans and cost objects ---------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Loan> loans(TenantContext ctx) {
        return jdbc.sql("""
                        select l.id, l.name, l.lender_party_id, p.name, l.start_date,
                               coalesce((select sum(j.credit - j.debit) from sf_journal_lines j where j.tenant_id = l.tenant_id and j.loan_id = l.id and j.account_code = '2200'), 0)
                        from sf_loans l join sf_parties p on p.id = l.lender_party_id and p.tenant_id = l.tenant_id
                        where l.tenant_id = :t order by l.start_date desc, l.id desc""")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new Loan(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getObject(5, LocalDate.class), rs.getBigDecimal(6)))
                .list();
    }

    public BigDecimal loanOutstanding(long tenantId, long loanId) {
        return jdbc.sql("select coalesce(sum(credit - debit), 0) from sf_journal_lines where tenant_id = :t and loan_id = :l and account_code = '2200'")
                .param("t", tenantId).param("l", loanId).query(BigDecimal.class).single();
    }

    @Transactional
    public List<CostObject> costObjects(TenantContext ctx) {
        settings.ensureDefaults(ctx.tenantId());
        return jdbc.sql("select id, kind, name, crop_cycle_id, farm_id, active from sf_cost_objects where tenant_id = :t order by kind desc, name")
                .param("t", ctx.tenantId())
                .query((rs, i) -> new CostObject(rs.getLong(1), rs.getString(2), rs.getString(3), (Long) rs.getObject(4), (Long) rs.getObject(5), rs.getBoolean(6)))
                .list();
    }

    public void requireCostObject(long tenantId, long id) {
        if (!jdbc.sql("select exists (select 1 from sf_cost_objects where id = :id and tenant_id = :t)").param("id", id).param("t", tenantId)
                .query(Boolean.class).single()) {
            throw ApiException.notFound("COST_OBJECT_NOT_FOUND", "Activity not found.");
        }
    }

    public void requireFarm(long tenantId, long id) {
        if (!jdbc.sql("select exists (select 1 from sf_farms where id = :id and tenant_id = :t)").param("id", id).param("t", tenantId)
                .query(Boolean.class).single()) {
            throw ApiException.notFound("FARM_NOT_FOUND", "Farm not found.");
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
