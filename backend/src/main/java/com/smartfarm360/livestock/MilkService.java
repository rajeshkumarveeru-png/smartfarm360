package com.smartfarm360.livestock;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.inventory.ItemService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.livestock.HerdService.AnimalRow;
import com.smartfarm360.livestock.LivestockDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import com.smartfarm360.platform.web.Versions;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Milk (livestock design, section 4): sessions by group with optional per-animal yields (I5), each day's milk in stock as
 * nil-value produce (I6), every litre's exit (sale, home use, fed to calves, discarded), and dairy society sales (I8).
 */
@Service
public class MilkService {
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final JdbcClient jdbc;
    private final HerdService herd;
    private final ItemService items;
    private final StockRecordService stock;
    private final DocumentService documents;
    private final AuditService audit;

    public MilkService(JdbcClient jdbc, HerdService herd, ItemService items, StockRecordService stock, DocumentService documents, AuditService audit) {
        this.jdbc = jdbc;
        this.herd = herd;
        this.items = items;
        this.stock = stock;
        this.documents = documents;
        this.audit = audit;
    }

    /** The tenant's Milk produce item (litres, nil value, Milk sales), created or adopted on first use. */
    @Transactional
    public long milkItem(TenantContext ctx) {
        Optional<Long> set = jdbc.sql("select milk_item_id from sf_livestock_settings where tenant_id = :t").param("t", ctx.tenantId()).query(Long.class).optional();
        if (set.isPresent()) return set.get();
        long id = jdbc.sql("select id from sf_stock_items where tenant_id = :t and lower(name) = 'milk' and kind = 'PRODUCE' and base_unit_code = 'litre'")
                .param("t", ctx.tenantId()).query(Long.class).optional()
                .orElseGet(() -> {
                    if (jdbc.sql("select exists (select 1 from sf_stock_items where tenant_id = :t and lower(name) = 'milk')").param("t", ctx.tenantId())
                            .query(Boolean.class).single()) {
                        throw ApiException.conflict("MILK_ITEM_CONFLICT", "A stock item named Milk exists but is not farm produce in litres. Rename it first.");
                    }
                    return items.createItem(ctx, new StockDtos.ItemRequest("Milk", "PRODUCE", "litre", null, herd.systemCategory("MILK_SALE"),
                            BigDecimal.ZERO, false, "Milk from the herd (livestock records).", null)).id();
                });
        jdbc.sql("insert into sf_livestock_settings (tenant_id, milk_item_id) values (:t, :i) on conflict (tenant_id) do nothing")
                .param("t", ctx.tenantId()).param("i", id).update();
        return jdbc.sql("select milk_item_id from sf_livestock_settings where tenant_id = :t").param("t", ctx.tenantId()).query(Long.class).single();
    }

    // ---- Sessions (I5) -------------------------------------------------------------------------------------------------

    @Transactional
    public MilkDay day(TenantContext ctx, LocalDate date) {
        List<Session> sessions = jdbc.sql("""
                        select s.id, s.session, s.group_id, co.name, s.litres, s.fat, s.snf, s.notes from sf_milk_sessions s
                        join sf_animal_groups g on g.id = s.group_id and g.tenant_id = s.tenant_id
                        join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = s.tenant_id
                        where s.tenant_id = :t and s.milk_date = :d order by co.name, s.session desc""")
                .param("t", ctx.tenantId()).param("d", date)
                .query((rs, i) -> {
                    List<YieldEntry> y = yields(ctx.tenantId(), rs.getLong(1));
                    return new Session(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getBigDecimal(5), rs.getBigDecimal(6),
                            rs.getBigDecimal(7), y, rs.getString(8), sessionVersion(rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getString(8), y));
                }).list();
        BigDecimal total = sessions.stream().map(Session::litres).reduce(ZERO, BigDecimal::add);
        BigDecimal inStock = items.item(ctx, milkItem(ctx)).quantity();
        return new MilkDay(date, sessions, total, withheld(ctx.tenantId(), date), inStock);
    }

    /** The version of a milking session: a hash of what the user recorded (mobile offline design, N7). */
    private static String sessionVersion(BigDecimal litres, BigDecimal fat, BigDecimal snf, String notes, List<YieldEntry> yields) {
        List<Object> parts = new ArrayList<>(List.of(litres));
        parts.add(fat);
        parts.add(snf);
        parts.add(notes);
        yields.forEach(y -> { parts.add(y.animalId()); parts.add(y.litres()); });
        return Versions.token(parts.toArray());
    }

    private String storedSessionVersion(long tenantId, LocalDate date, String session, long groupId) {
        return jdbc.sql("select id, litres, fat, snf, notes from sf_milk_sessions where tenant_id = :t and milk_date = :d and session = :s and group_id = :g")
                .param("t", tenantId).param("d", date).param("s", session).param("g", groupId)
                .query((rs, i) -> sessionVersion(rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getString(5), yields(tenantId, rs.getLong(1))))
                .optional().orElse(Versions.NONE);
    }

    private List<YieldEntry> yields(long tenantId, long sessionId) {
        return jdbc.sql("select animal_id, litres from sf_milk_yields where tenant_id = :t and session_id = :s order by animal_id")
                .param("t", tenantId).param("s", sessionId).query((rs, i) -> new YieldEntry(rs.getLong(1), rs.getBigDecimal(2))).list();
    }

    /** Animals whose milk must not be sold on a date: a withdrawal on the animal, or on the group it was in (I9). */
    List<WithheldAnimal> withheld(long tenantId, LocalDate date) {
        return jdbc.sql("""
                        select a.id, a.tag, a.name, max(e.withdrawal_until) from sf_animals a
                        join sf_animal_events e on e.tenant_id = a.tenant_id and e.withdrawal_until >= :d and e.event_date <= :d
                          and (e.animal_id = a.id or (e.animal_id is null and e.group_id = (select m.group_id from sf_animal_group_moves m
                               where m.tenant_id = a.tenant_id and m.animal_id = a.id and m.from_date <= :d order by m.from_date desc, m.id desc limit 1)))
                        where a.tenant_id = :t and a.sex = 'FEMALE' and (a.exit_date is null or a.exit_date > :d)
                        group by a.id, a.tag, a.name order by lower(a.tag)""")
                .param("t", tenantId).param("d", date)
                .query((rs, i) -> new WithheldAnimal(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class))).list();
    }

    @Transactional
    public MilkDay saveDay(TenantContext ctx, LocalDate date, MilkDayRequest r) {
        if (date.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        Set<Long> touched = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        // One save of a day at a time, so the version checks and the writes see the same day (N7)
        jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "milk:" + ctx.tenantId() + ":" + date).query().listOfRows();
        for (SessionEntry s : r.sessions()) {
            if (!seen.add(s.session() + "|" + s.groupId())) throw ApiException.badRequest("DUPLICATE_LINE", "Each group's session can appear only once.");
            Group g = herd.group(ctx, s.groupId());
            if (s.baseVersion() != null) Versions.requireCurrent(s.baseVersion(), storedSessionVersion(ctx.tenantId(), date, s.session(), g.id()));
            List<YieldEntry> yields = s.yields() == null ? List.of() : s.yields();
            Set<Long> animals = new HashSet<>();
            for (YieldEntry y : yields) {
                if (!animals.add(y.animalId())) throw ApiException.badRequest("DUPLICATE_LINE", "Each animal can appear only once per session.");
                AnimalRow a = herd.animalRow(ctx.tenantId(), y.animalId());
                boolean here = !date.isBefore(a.arrivedOn()) && (a.exitDate() == null || !date.isAfter(a.exitDate()))
                        && herd.groupOn(ctx.tenantId(), a.id(), date).filter(x -> x == g.id()).isPresent();
                if (!here) throw ApiException.badRequest("ANIMAL_NOT_IN_GROUP", a.tag() + " was not in " + g.name() + " on that day.");
            }
            BigDecimal litres = yields.isEmpty() ? s.litres() : yields.stream().map(YieldEntry::litres).reduce(ZERO, BigDecimal::add);
            Optional<Long> existing = jdbc.sql("select id from sf_milk_sessions where tenant_id = :t and milk_date = :d and session = :s and group_id = :g")
                    .param("t", ctx.tenantId()).param("d", date).param("s", s.session()).param("g", g.id()).query(Long.class).optional();
            if (litres == null) {
                existing.ifPresent(id -> jdbc.sql("delete from sf_milk_sessions where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update());
            } else {
                if (!g.active()) throw ApiException.badRequest("ANIMAL_GROUP_CLOSED", "This group is closed.");
                long id = existing.map(x -> {
                    jdbc.sql("update sf_milk_sessions set litres = :l, fat = :f, snf = :snf, notes = :n, updated_at = now() where id = :id and tenant_id = :t")
                            .param("l", litres).param("f", s.fat()).param("snf", s.snf()).param("n", HerdService.trim(s.notes())).param("id", x)
                            .param("t", ctx.tenantId()).update();
                    return x;
                }).orElseGet(() -> jdbc.sql("""
                                insert into sf_milk_sessions (tenant_id, milk_date, session, group_id, litres, fat, snf, notes, created_by)
                                values (:t, :d, :s, :g, :l, :f, :snf, :n, :u) returning id""")
                        .param("t", ctx.tenantId()).param("d", date).param("s", s.session()).param("g", g.id()).param("l", litres).param("f", s.fat())
                        .param("snf", s.snf()).param("n", HerdService.trim(s.notes())).param("u", ctx.userId()).query(Long.class).single());
                jdbc.sql("delete from sf_milk_yields where tenant_id = :t and session_id = :s").param("t", ctx.tenantId()).param("s", id).update();
                for (YieldEntry y : yields) {
                    jdbc.sql("insert into sf_milk_yields (tenant_id, session_id, animal_id, litres) values (:t, :s, :a, :l)")
                            .param("t", ctx.tenantId()).param("s", id).param("a", y.animalId()).param("l", y.litres()).update();
                }
            }
            touched.add(g.id());
        }
        for (long g : touched) repost(ctx, date, g);
        audit.record(ctx.tenantId(), ctx.userId(), "MILK_RECORDED", "MILK_DAY", date.toString(), Map.of("sessions", r.sessions().size()));
        return day(ctx, date);
    }

    /**
     * Keeps the day's produce records equal to the group's sessions (I6). More milk adds a produce record for the
     * difference; less milk reverses the day's records and records the new total, which stock refuses once the milk has
     * been used or sold (then the difference must be recorded as discarded).
     */
    private void repost(TenantContext ctx, LocalDate date, long groupId) {
        BigDecimal target = jdbc.sql("select coalesce(sum(litres), 0) from sf_milk_sessions where tenant_id = :t and milk_date = :d and group_id = :g")
                .param("t", ctx.tenantId()).param("d", date).param("g", groupId).query(BigDecimal.class).single().setScale(2, RoundingMode.HALF_UP);
        List<Map<String, Object>> posted = jdbc.sql("""
                        select p.id, p.document_id, p.litres from sf_milk_postings p join sf_finance_documents d on d.id = p.document_id and d.tenant_id = p.tenant_id
                        where p.tenant_id = :t and p.milk_date = :d and p.group_id = :g and d.status = 'POSTED' order by p.id desc""")
                .param("t", ctx.tenantId()).param("d", date).param("g", groupId).query().listOfRows();
        BigDecimal current = posted.stream().map(m -> (BigDecimal) m.get("litres")).reduce(ZERO, BigDecimal::add);
        int cmp = target.compareTo(current);
        if (cmp == 0) return;
        if (cmp > 0) {
            produce(ctx, date, groupId, target.subtract(current));
            return;
        }
        for (Map<String, Object> p : posted) {
            try {
                documents.reverse(ctx, ((Number) p.get("document_id")).longValue(), null);
            } catch (ApiException e) {
                throw ApiException.conflict("MILK_ALREADY_USED", "Some of this day's milk is already sold or used. Record the difference as discarded milk instead.");
            }
        }
        if (target.signum() > 0) produce(ctx, date, groupId, target);
    }

    private void produce(TenantContext ctx, LocalDate date, long groupId, BigDecimal litres) {
        Group g = herd.group(ctx, groupId);
        Document doc = stock.produce(ctx, new StockDtos.ProduceRequest(date, null, g.costObjectId(), g.farmId(),
                List.of(new StockDtos.ItemQuantity(milkItem(ctx), litres, null, null, null)), "Milk, " + g.name()), null).document();
        jdbc.sql("insert into sf_milk_postings (tenant_id, milk_date, group_id, document_id, litres) values (:t, :d, :g, :doc, :l)")
                .param("t", ctx.tenantId()).param("d", date).param("g", groupId).param("doc", doc.summary().id()).param("l", litres).update();
    }

    // ---- Where milk goes (I6) ---------------------------------------------------------------------------------------------

    @Transactional
    public List<RecordRef> uses(TenantContext ctx, MilkUseRequest r, String key) {
        if (r.date().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        long milk = milkItem(ctx);
        List<RecordRef> out = new ArrayList<>();
        if (positive(r.homeUse())) {
            out.add(ref(stock.issue(ctx, new StockDtos.IssueRequest(r.date(), null, null, null, List.of(line(milk, r.homeUse())), "Milk for home use"),
                    sub(key, "home")).document()));
        }
        if (positive(r.fedLitres())) {
            if (r.fedGroupId() == null) throw ApiException.badRequest("GROUP_REQUIRED", "Choose the group the milk was fed to.");
            Group g = herd.group(ctx, r.fedGroupId());
            out.add(ref(stock.issue(ctx, new StockDtos.IssueRequest(r.date(), null, g.costObjectId(), g.farmId(), List.of(line(milk, r.fedLitres())),
                    "Milk fed to " + g.name()), sub(key, "fed")).document()));
        }
        if (positive(r.discarded())) {
            String reason = HerdService.trim(r.discardReason());
            if (reason == null) throw ApiException.badRequest("REASON_REQUIRED", "Say why the milk was discarded.");
            out.add(ref(stock.wastage(ctx, new StockDtos.WastageRequest(r.date(), null, reason, List.of(line(milk, r.discarded()))), sub(key, "discarded")).document()));
        }
        if (out.isEmpty()) throw ApiException.badRequest("NOTHING_TO_RECORD", "Enter the litres.");
        audit.record(ctx.tenantId(), ctx.userId(), "MILK_USE_RECORDED", "MILK_DAY", r.date().toString(), Map.of("records", out.size()));
        return out;
    }

    // ---- Selling milk (I8) ------------------------------------------------------------------------------------------------

    @Transactional
    public RecordRef sale(TenantContext ctx, MilkSaleRequest r, String key) {
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a sale needs permission to record money.");
        if (r.partyId() == null && r.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose the buyer or the account the money came into.");
        if (r.periodFrom() != null && r.periodTo() != null && r.periodTo().isBefore(r.periodFrom())) throw ApiException.badRequest("INVALID_DATE_RANGE", "Choose a valid period.");
        Group g = herd.group(ctx, r.groupId());
        String description = r.periodFrom() != null && r.periodTo() != null ? "Milk " + r.periodFrom() + " to " + r.periodTo() : "Milk";
        Document sale = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, r.partyId(), g.farmId(),
                r.partyId() == null ? r.financialAccountId() : null,
                List.of(new LineRequest(herd.systemCategory("MILK_SALE"), description, r.litres(), null, null, r.amount(), g.costObjectId(), g.farmId(),
                        milkItem(ctx), null, null, null, null)),
                null, r.referenceNo(), true, r.paidNow()), key);
        return ref(sale);
    }

    private static boolean positive(BigDecimal v) { return v != null && v.signum() > 0; }

    private static StockDtos.ItemQuantity line(long item, BigDecimal litres) { return new StockDtos.ItemQuantity(item, litres, null, null, null); }

    private static String sub(String key, String part) { return key == null ? null : key + ":" + part; }

    private static RecordRef ref(Document d) {
        return new RecordRef(d.summary().id(), d.summary().number(), d.summary().type(), d.summary().totalAmount());
    }
}
