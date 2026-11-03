package com.smartfarm360.finance;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.FinanceDtos.FinanceSettings;
import com.smartfarm360.finance.FinanceDtos.FinanceSettingsRequest;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/** Lock date (D9), fiscal year (D1) and the defaults every tenant needs before recording money. */
@Service
public class FinanceSettingsService {
    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final AuditService audit;

    public FinanceSettingsService(JdbcClient jdbc, CostObjectService costObjects, AuditService audit) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.audit = audit;
    }

    /** Ensures the tenant has its settings row, an overhead cost object and a default "Cash in hand" account. */
    @Transactional
    public void ensureDefaults(long tenantId) {
        jdbc.sql("insert into sf_tenant_settings (tenant_id) values (:t) on conflict do nothing").param("t", tenantId).update();
        costObjects.overhead(tenantId);
        boolean hasAccount = jdbc.sql("select exists (select 1 from sf_financial_accounts where tenant_id = :t)")
                .param("t", tenantId).query(Boolean.class).single();
        if (!hasAccount) {
            jdbc.sql("insert into sf_financial_accounts (tenant_id, name, kind, account_code) values (:t, 'Cash in hand', 'CASH', '1000') on conflict do nothing")
                    .param("t", tenantId).update();
        }
    }

    @Transactional(readOnly = true)
    public FinanceSettings settings(long tenantId) {
        return jdbc.sql("select lock_date, fiscal_year_start_month from sf_tenant_settings where tenant_id = :t")
                .param("t", tenantId)
                .query((rs, i) -> new FinanceSettings(rs.getObject(1, LocalDate.class), rs.getInt(2)))
                .optional().orElse(new FinanceSettings(null, 4));
    }

    @Transactional
    public FinanceSettings update(TenantContext ctx, FinanceSettingsRequest request) {
        ensureDefaults(ctx.tenantId());
        FinanceSettings before = settings(ctx.tenantId());
        jdbc.sql("update sf_tenant_settings set lock_date = :lock, fiscal_year_start_month = :month, updated_at = now() where tenant_id = :t")
                .param("lock", request.lockDate()).param("month", request.fiscalYearStartMonth()).param("t", ctx.tenantId()).update();
        Map<String, Object> details = new HashMap<>();
        details.put("lockDateBefore", before.lockDate() == null ? null : before.lockDate().toString());
        details.put("lockDate", request.lockDate() == null ? null : request.lockDate().toString());
        details.put("fiscalYearStartMonth", request.fiscalYearStartMonth());
        audit.record(ctx.tenantId(), ctx.userId(), "FINANCE_SETTINGS_UPDATED", "TENANT_SETTINGS", ctx.tenantId(), details);
        return settings(ctx.tenantId());
    }

    /** Refuses changes dated on or before the lock date. */
    public void requireOpen(long tenantId, LocalDate date) {
        LocalDate lock = settings(tenantId).lockDate();
        if (lock != null && !date.isAfter(lock)) {
            throw ApiException.conflict("PERIOD_LOCKED", "Records on or before " + lock + " are locked.");
        }
    }

    /** First day of the fiscal year containing {@code date}. */
    public static LocalDate fiscalYearStart(LocalDate date, int startMonth) {
        int year = date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
        return LocalDate.of(year, startMonth, 1);
    }

    /** Label used in document numbers: "2026-27" for April–March, "2026" for calendar years. */
    public static String fiscalYearLabel(LocalDate date, int startMonth) {
        int start = fiscalYearStart(date, startMonth).getYear();
        return startMonth == 1 ? String.valueOf(start) : start + "-" + String.format("%02d", (start + 1) % 100);
    }
}
