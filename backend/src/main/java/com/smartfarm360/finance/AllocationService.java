package com.smartfarm360.finance;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.FinanceDtos.*;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Overhead allocation (posting rule P16, crop design F6): farm overhead of a period is spread to the activities that
 * ran in it, by area × days. For each overhead cost: Dr the same expense on the activity / Cr it on overhead, so the
 * farm's totals never change. Interest, losses and cost of goods sold are never allocated.
 */
@Service
public class AllocationService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final List<String> ALLOCATABLE = Arrays.stream(LedgerAccount.values())
            .filter(a -> a.isOperatingCost() && a != LedgerAccount.COST_OF_GOODS_SOLD).map(LedgerAccount::code).toList();

    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final DocumentService documents;

    public AllocationService(JdbcClient jdbc, CostObjectService costObjects, DocumentService documents) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.documents = documents;
    }

    private record Target(long costObjectId, String name, Long farmId, BigDecimal acres, LocalDate from, LocalDate to) {}

    @Transactional(readOnly = true)
    public AllocationPreview preview(TenantContext ctx, LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > 366) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a period of at most one year.");
        }
        long overhead = costObjects.overhead(ctx.tenantId());
        List<Target> targets = jdbc.sql("""
                        select id, name, farm_id, area_acres, active_from, active_to from sf_cost_objects
                        where tenant_id = :t and kind = 'CROP_CYCLE' and active and area_acres is not null and active_from <= :to
                          and (active_to is null or active_to >= :from) order by id""")
                .param("t", ctx.tenantId()).param("from", from).param("to", to)
                .query((rs, i) -> new Target(rs.getLong(1), rs.getString(2), (Long) rs.getObject(3), rs.getBigDecimal(4),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class)))
                .list();
        List<AllocationGroup> groups = new ArrayList<>();
        BigDecimal allocated = ZERO, notAllocated = ZERO;
        List<Map<String, Object>> rows = jdbc.sql("""
                        select l.account_code, l.category_id, c.code, c.name, l.farm_id, sum(l.debit - l.credit) as amount
                        from sf_journal_lines l join sf_journal_entries e on e.id = l.entry_id
                        join sf_finance_documents d on d.id = e.document_id
                        left join sf_finance_categories c on c.id = l.category_id
                        where l.tenant_id = :t and l.cost_object_id = :o and l.account_code in (:codes) and e.entry_date between :from and :to
                          and d.doc_type <> 'ALLOCATION'
                        group by l.account_code, l.category_id, c.code, c.name, l.farm_id
                        having sum(l.debit - l.credit) > 0 order by l.account_code, l.category_id nulls first, l.farm_id nulls first""")
                .param("t", ctx.tenantId()).param("o", overhead).param("codes", ALLOCATABLE).param("from", from).param("to", to)
                .query().listOfRows();
        for (Map<String, Object> r : rows) {
            Long farm = r.get("farm_id") == null ? null : ((Number) r.get("farm_id")).longValue();
            BigDecimal amount = ((BigDecimal) r.get("amount")).setScale(2, RoundingMode.HALF_UP);
            // A cost named for a farm goes to that farm's activities only
            List<Target> eligible = targets.stream().filter(x -> farm == null || farm.equals(x.farmId())).toList();
            List<AllocationShare> shares = shares(eligible, from, to, amount);
            if (shares.isEmpty()) notAllocated = notAllocated.add(amount);
            else allocated = allocated.add(amount);
            groups.add(new AllocationGroup((String) r.get("account_code"), r.get("category_id") == null ? null : ((Number) r.get("category_id")).longValue(),
                    (String) r.get("code"), (String) r.get("name"), farm, amount, shares));
        }
        return new AllocationPreview(from, to, groups, allocated, notAllocated);
    }

    /** Shares rounded to the paisa; the last activity takes the remainder so the total is exact. */
    static List<AllocationShare> shares(List<Target> targets, LocalDate from, LocalDate to, BigDecimal amount) {
        List<AllocationShare> out = new ArrayList<>();
        BigDecimal totalWeight = BigDecimal.ZERO;
        List<BigDecimal> weights = new ArrayList<>();
        List<Long> days = new ArrayList<>();
        for (Target x : targets) {
            LocalDate start = x.from().isAfter(from) ? x.from() : from;
            LocalDate end = x.to() != null && x.to().isBefore(to) ? x.to() : to;
            long d = end.isBefore(start) ? 0 : ChronoUnit.DAYS.between(start, end) + 1;
            BigDecimal w = x.acres().multiply(BigDecimal.valueOf(d));
            days.add(d);
            weights.add(w);
            totalWeight = totalWeight.add(w);
        }
        if (totalWeight.signum() == 0) return out;
        BigDecimal remaining = amount;
        int last = -1;
        for (int i = 0; i < targets.size(); i++) if (weights.get(i).signum() > 0) last = i;
        for (int i = 0; i < targets.size(); i++) {
            if (weights.get(i).signum() == 0) continue;
            BigDecimal share = i == last ? remaining : amount.multiply(weights.get(i)).divide(totalWeight, 2, RoundingMode.HALF_UP);
            remaining = remaining.subtract(share);
            Target x = targets.get(i);
            out.add(new AllocationShare(x.costObjectId(), x.name(), x.acres(), days.get(i), weights.get(i).stripTrailingZeros(), share));
        }
        return out;
    }

    /** Posts the allocation as a reversible finance record; one per period (reverse it to run again). */
    @Transactional
    public FinanceDtos.Document allocate(TenantContext ctx, OverheadAllocationRequest r, String key) {
        Optional<FinanceDtos.Document> replay = documents.replayed(ctx, key, DocType.ALLOCATION, r);
        if (replay.isPresent()) return replay.get();
        boolean exists = jdbc.sql("""
                        select exists (select 1 from sf_finance_documents where tenant_id = :t and doc_type = 'ALLOCATION' and status = 'POSTED'
                          and period_from <= :to and period_to >= :from)""")
                .param("t", ctx.tenantId()).param("from", r.from()).param("to", r.to()).query(Boolean.class).single();
        if (exists) {
            throw ApiException.conflict("ALLOCATION_EXISTS", "Overhead for part of this period is already allocated. Reverse that allocation first.");
        }
        AllocationPreview p = preview(ctx, r.from(), r.to());
        if (p.allocated().signum() == 0) {
            throw ApiException.badRequest("NOTHING_TO_ALLOCATE", "There is no farm overhead to spread to growing crops in this period.");
        }
        long overhead = costObjects.overhead(ctx.tenantId());
        List<JournalPart> parts = new ArrayList<>();
        List<DocumentService.AllocationLine> lines = new ArrayList<>();
        for (AllocationGroup g : p.groups()) {
            if (g.shares().isEmpty()) continue;
            for (AllocationShare s : g.shares()) {
                if (s.amount().signum() == 0) continue;
                parts.add(new JournalPart(g.accountCode(), s.amount(), BigDecimal.ZERO, g.categoryId(), null, null, s.costObjectId(), g.farmId()));
                lines.add(new DocumentService.AllocationLine(g.accountCode(), g.categoryId(), s.costObjectId(), g.farmId(), s.amount()));
            }
            parts.add(new JournalPart(g.accountCode(), BigDecimal.ZERO, g.amount(), g.categoryId(), null, null, overhead, g.farmId()));
        }
        return documents.recordAllocation(ctx, r.from(), r.to(), p.allocated(), lines, parts, r.notes(), key, r);
    }
}
