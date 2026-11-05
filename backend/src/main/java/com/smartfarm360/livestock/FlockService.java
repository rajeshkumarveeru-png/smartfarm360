package com.smartfarm360.livestock;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.inventory.ItemService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.livestock.FlockDtos.*;
import com.smartfarm360.livestock.HerdService.AnimalRow;
import com.smartfarm360.livestock.LivestockDtos.Group;
import com.smartfarm360.livestock.LivestockDtos.GroupRequest;
import com.smartfarm360.livestock.LivestockDtos.RecordRef;
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
 * Poultry batches and goat and sheep herds (poultry, goats and sheep design): counted groups whose heads change only through
 * dated movements (J7), bought young stock as group costs (J3), the daily batch record with eggs into stock and feed as
 * stock use (J4, J5, J13), sales by head and weight (J6), kidding and lambing (J10) and copied health plans (J9).
 */
@Service
public class FlockService {
    static final Set<String> IN = Set.of("PLACED", "BOUGHT", "BORN", "MOVED_IN", "COUNT_GAIN");

    private final JdbcClient jdbc;
    private final HerdService herd;
    private final DocumentService documents;
    private final StockRecordService stock;
    private final ItemService items;
    private final AuditService audit;

    public FlockService(JdbcClient jdbc, HerdService herd, DocumentService documents, StockRecordService stock, ItemService items, AuditService audit) {
        this.jdbc = jdbc;
        this.herd = herd;
        this.documents = documents;
        this.stock = stock;
        this.items = items;
        this.audit = audit;
    }

    private static void notFuture(LocalDate d) {
        if (d.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
    }

    Group counted(TenantContext ctx, long groupId) {
        Group g = herd.group(ctx, groupId);
        if (!g.counted()) throw ApiException.badRequest("NOT_A_COUNTED_GROUP", "Head counts are kept for poultry batches and goat and sheep herds.");
        if (!ctx.enabledModules().contains(HerdService.moduleFor(g.species()))) {
            throw ApiException.forbidden("MODULE_DISABLED", "This module is not enabled for your farm business.");
        }
        return g;
    }

    // ---- Head counts (J7) ------------------------------------------------------------------------------------------

    record Move(LocalDate date, String kind, int heads) { int signed() { return IN.contains(kind) ? heads : -heads; } }

    /** The group's movements that count: those without a finance record, or whose record is posted. */
    List<Move> moves(long tenantId, long groupId) {
        return jdbc.sql("""
                        select m.move_date, m.kind, m.heads from sf_head_movements m
                        left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.group_id = :g and (m.document_id is null or d.status = 'POSTED') order by m.move_date, m.id""")
                .param("t", tenantId).param("g", groupId).query((rs, i) -> new Move(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getInt(3))).list();
    }

    /** A movement with its variety, for the figures by variety. */
    record LineMove(long lineId, Move move, BigDecimal kg) {}

    List<LineMove> lineMoves(long tenantId, long groupId) {
        return jdbc.sql("""
                        select m.line_id, m.move_date, m.kind, m.heads, m.kg from sf_head_movements m
                        left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
                        where m.tenant_id = :t and m.group_id = :g and m.line_id is not null and (m.document_id is null or d.status = 'POSTED') order by m.move_date, m.id""")
                .param("t", tenantId).param("g", groupId)
                .query((rs, i) -> new LineMove(rs.getLong(1), new Move(rs.getObject(2, LocalDate.class), rs.getString(3), rs.getInt(4)), rs.getBigDecimal(5))).list();
    }

    int heads(long tenantId, long groupId, LocalDate date) {
        return moves(tenantId, groupId).stream().filter(m -> !m.date().isAfter(date)).mapToInt(Move::signed).sum();
    }

    /** Refuses any movement that would leave the group with fewer than zero heads on any day (J7). */
    private void requireNonNegative(TenantContext ctx, Group g) {
        int running = 0;
        List<Move> list = moves(ctx.tenantId(), g.id());
        for (int i = 0; i < list.size(); i++) {
            running += list.get(i).signed();
            boolean endOfDay = i == list.size() - 1 || !list.get(i + 1).date().equals(list.get(i).date());
            if (endOfDay && running < 0) {
                throw ApiException.conflict("HEADS_NEGATIVE", g.name() + " would have fewer than none on " + list.get(i).date() + ".");
            }
        }
        requireLinesNonNegative(ctx, g);
    }

    private long insertMove(TenantContext ctx, long groupId, LocalDate date, String kind, int heads, BigDecimal kg, String cause, Long documentId,
                            Long batchDayId, Long pairId, long lineId) {
        return jdbc.sql("""
                        insert into sf_head_movements (tenant_id, group_id, move_date, kind, heads, kg, cause, document_id, batch_day_id, pair_id, created_by, line_id)
                        values (:t, :g, :d, :k, :h, :kg, :c, :doc, :day, :pair, :u, :line) returning id""")
                .param("t", ctx.tenantId()).param("g", groupId).param("d", date).param("k", kind).param("h", heads).param("kg", kg)
                .param("c", HerdService.trim(cause)).param("doc", documentId).param("day", batchDayId).param("pair", pairId).param("u", ctx.userId())
                .param("line", lineId).query(Long.class).single();
    }

    // ---- Varieties (bin and batch varieties design, V1-V7) ----

    private static final Set<String> BIRDS = Set.of("CHICKEN", "DUCK", "QUAIL", "TURKEY");

    /** The group's varieties with their heads on {@code date}; movements of reversed bills do not count. */
    List<FlockLine> lines(long tenantId, long groupId, LocalDate date) {
        return jdbc.sql("""
                        select l.id, l.species, l.variety,
                          coalesce((select sum(case when hm.kind in ('PLACED', 'BOUGHT', 'BORN', 'MOVED_IN', 'COUNT_GAIN') then hm.heads else -hm.heads end)
                             from sf_head_movements hm left join sf_finance_documents hd on hd.id = hm.document_id and hd.tenant_id = hm.tenant_id
                             where hm.tenant_id = l.tenant_id and hm.line_id = l.id and hm.move_date <= :d and (hm.document_id is null or hd.status = 'POSTED')), 0)
                        from sf_flock_lines l where l.tenant_id = :t and l.group_id = :g order by l.id""")
                .param("t", tenantId).param("g", groupId).param("d", date)
                .query((rs, i) -> new FlockLine(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4))).list();
    }

    public List<FlockLine> lines(TenantContext ctx, long groupId) {
        Group g = counted(ctx, groupId);
        return lines(ctx.tenantId(), g.id(), LocalDate.now());
    }

    private long newLine(TenantContext ctx, Group g, String species, String variety) {
        String sp = species != null ? species : "CHICKEN".equals(g.species()) ? "CHICKEN" : g.species();
        boolean fits = "CHICKEN".equals(g.species()) ? BIRDS.contains(sp) : Set.of("GOAT", "SHEEP").contains(sp);
        if (!fits) throw ApiException.badRequest("SPECIES_MISMATCH", "This kind of animal does not belong in this group.");
        return jdbc.sql("insert into sf_flock_lines (tenant_id, group_id, species, variety) values (:t, :g, :s, :v) returning id")
                .param("t", ctx.tenantId()).param("g", g.id()).param("s", sp).param("v", HerdService.trim(variety)).query(Long.class).single();
    }

    /** The variety a change happens to: the one named, or the group's only one. */
    private long line(TenantContext ctx, Group g, Long lineId) {
        List<Long> ids = jdbc.sql("select id from sf_flock_lines where tenant_id = :t and group_id = :g order by id")
                .param("t", ctx.tenantId()).param("g", g.id()).query(Long.class).list();
        if (lineId != null) {
            if (!ids.contains(lineId)) throw ApiException.notFound("LINE_NOT_FOUND", "That variety is not in this batch.");
            return lineId;
        }
        if (ids.isEmpty()) return newLine(ctx, g, null, g.strain());
        if (ids.size() > 1) throw ApiException.badRequest("LINE_REQUIRED", "Choose the variety.");
        return ids.get(0);
    }

    /** A named line, a new variety (the same species and name again is the same line), or the group's only line. */
    private long line(TenantContext ctx, Group g, Long lineId, String species, String variety) {
        String name = HerdService.trim(variety);
        if (lineId == null && (name != null || species != null)) {
            String sp = species != null ? species : "CHICKEN".equals(g.species()) ? "CHICKEN" : g.species();
            return jdbc.sql("""
                            select id from sf_flock_lines where tenant_id = :t and group_id = :g and species = :s
                              and coalesce(lower(variety), '') = coalesce(lower(cast(:v as varchar)), '') order by id limit 1""")
                    .param("t", ctx.tenantId()).param("g", g.id()).param("s", sp).param("v", name).query(Long.class).optional()
                    .orElseGet(() -> newLine(ctx, g, sp, name));
        }
        return line(ctx, g, lineId);
    }

    private int lineHeads(TenantContext ctx, Group g, long lineId, LocalDate date) {
        return lines(ctx.tenantId(), g.id(), date).stream().filter(l -> l.id() == lineId).mapToInt(FlockLine::heads).findFirst().orElse(0);
    }

    /** Refuses a change that would leave any variety with fewer than none on some day. */
    private void requireLinesNonNegative(TenantContext ctx, Group g) {
        jdbc.sql("""
                        select l.species, l.variety, x.d from (
                          select hm.line_id, hm.move_date as d,
                                 sum(sum(case when hm.kind in ('PLACED', 'BOUGHT', 'BORN', 'MOVED_IN', 'COUNT_GAIN') then hm.heads else -hm.heads end))
                                   over (partition by hm.line_id order by hm.move_date) as running
                          from sf_head_movements hm left join sf_finance_documents hd on hd.id = hm.document_id and hd.tenant_id = hm.tenant_id
                          where hm.tenant_id = :t and hm.group_id = :g and hm.line_id is not null and (hm.document_id is null or hd.status = 'POSTED')
                          group by hm.line_id, hm.move_date) x
                        join sf_flock_lines l on l.id = x.line_id and l.tenant_id = :t
                        where x.running < 0 order by x.d limit 1""")
                .param("t", ctx.tenantId()).param("g", g.id())
                .query((rs, i) -> {
                    String label = rs.getString(2) != null ? rs.getString(2) : rs.getString(1);
                    throw ApiException.conflict("HEADS_NEGATIVE", label + " in " + g.name() + " would have fewer than none on " + rs.getObject(3, LocalDate.class) + ".");
                }).list();
    }

    /** A closed poultry batch takes no new birds; reversing its sale reopens it (J2, J14). */
    private void requireOpen(Group g, String kind) {
        if (g.closed() && IN.contains(kind) && !"COUNT_GAIN".equals(kind)) {
            throw ApiException.conflict("BATCH_CLOSED", g.name() + " is closed. Start a new batch for new birds.");
        }
    }

    @Transactional
    public Group place(TenantContext ctx, PlacementRequest r, String key) {
        notFuture(r.placedOn());
        boolean several = r.lines() != null && !r.lines().isEmpty();
        if (!several && r.heads() == null) throw ApiException.badRequest("HEADS_REQUIRED", "Enter the number of heads.");
        Group g = herd.createGroup(ctx, new GroupRequest(r.name(), r.purpose(), r.farmId(), r.notes(), r.species()), r.placedOn(), r.ageAtPlacementDays(), r.strain());
        List<VarietyLine> wanted = several ? r.lines()
                : List.of(new VarietyLine(null, null, r.strain(), r.heads(), r.bill() == null ? null : r.bill().amount()));
        List<Long> ids = new ArrayList<>();
        for (VarietyLine l : wanted) ids.add(line(ctx, g, null, l.species() != null ? l.species() : "CHICKEN".equals(r.species()) ? "CHICKEN" : r.species(), l.variety()));
        if (r.bill() != null) {
            long doc = bill(ctx, g, r.placedOn(), wanted, ids, r.bill(), key);
            for (int i = 0; i < wanted.size(); i++) {
                insertMove(ctx, g.id(), r.placedOn(), "CHICKEN".equals(r.species()) ? "PLACED" : "BOUGHT", wanted.get(i).heads(), null, null, doc, null, null, ids.get(i));
            }
        } else {
            for (int i = 0; i < wanted.size(); i++) insertMove(ctx, g.id(), r.placedOn(), "PLACED", wanted.get(i).heads(), null, "Received", null, null, null, ids.get(i));
        }
        audit.record(ctx.tenantId(), ctx.userId(), "FLOCK_PLACED", "ANIMAL_GROUP", g.id(),
                Map.of("heads", wanted.stream().mapToInt(VarietyLine::heads).sum(), "species", r.species(), "varieties", wanted.size()));
        return herd.group(ctx, g.id());
    }

    /** One bill for the heads bought, a line per variety; with one variety and no price of its own, the bill's amount. */
    private long bill(TenantContext ctx, Group g, LocalDate date, List<VarietyLine> wanted, List<Long> ids, Bill b, String key) {
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a bill needs permission to record money.");
        if (b.partyId() == null && b.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose who sold them or the account it was paid from.");
        List<FlockLine> known = lines(ctx.tenantId(), g.id(), date);
        List<LineRequest> billLines = new ArrayList<>();
        for (int i = 0; i < wanted.size(); i++) {
            VarietyLine l = wanted.get(i);
            BigDecimal amount = l.amount() != null ? l.amount() : wanted.size() == 1 ? b.amount() : null;
            if (amount == null) throw ApiException.badRequest("LINE_PRICE_REQUIRED", "Enter the price for each variety.");
            long id = ids.get(i);
            String variety = known.stream().filter(k -> k.id() == id).map(k -> k.variety() != null ? k.variety() : k.species().toLowerCase()).findFirst().orElse("");
            billLines.add(new LineRequest(herd.systemCategory("YOUNG_STOCK"), l.heads() + " " + variety + " for " + g.name(), BigDecimal.valueOf(l.heads()), null, null,
                    amount, g.costObjectId(), g.farmId()));
        }
        Document doc = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(date, null, b.partyId(), g.farmId(), b.partyId() == null ? b.financialAccountId() : null,
                billLines, null, b.referenceNo(), true, b.paidNow()), key);
        return doc.summary().id();
    }

    @Transactional
    public Movement purchase(TenantContext ctx, long groupId, PurchaseRequest r, String key) {
        notFuture(r.date());
        Group g = counted(ctx, groupId);
        requireOpen(g, "BOUGHT");
        boolean several = r.lines() != null && !r.lines().isEmpty();
        List<VarietyLine> wanted = several ? r.lines() : List.of(new VarietyLine(null, null, null, need(r.heads()), r.bill().amount()));
        List<Long> ids = new ArrayList<>();
        for (VarietyLine l : wanted) ids.add(several ? line(ctx, g, l.lineId(), l.species(), l.variety()) : line(ctx, g, null));
        long doc = bill(ctx, g, r.date(), wanted, ids, r.bill(), key);
        long first = 0;
        for (int i = 0; i < wanted.size(); i++) {
            long id = insertMove(ctx, g.id(), r.date(), "BOUGHT", wanted.get(i).heads(), null, null, doc, null, null, ids.get(i));
            if (i == 0) first = id;
        }
        audit.record(ctx.tenantId(), ctx.userId(), "HEADS_RECORDED", "ANIMAL_GROUP", g.id(),
                Map.of("kind", "BOUGHT", "heads", wanted.stream().mapToInt(VarietyLine::heads).sum()));
        return movement(ctx, first);
    }

    @Transactional
    public List<Movement> move(TenantContext ctx, long groupId, MovementRequest r) {
        notFuture(r.date());
        Group g = counted(ctx, groupId);
        if (g.placedOn() != null && r.date().isBefore(g.placedOn())) throw ApiException.badRequest("BEFORE_PLACEMENT", "The batch was placed after this date.");
        List<Long> ids = new ArrayList<>();
        switch (r.kind()) {
            case "COUNT" -> {
                if (r.countedHeads() == null) throw ApiException.badRequest("COUNT_REQUIRED", "Enter the number counted.");
                if (HerdService.trim(r.cause()) == null) throw ApiException.badRequest("REASON_REQUIRED", "Say why the count differs.");
                long lineId = line(ctx, g, r.lineId());
                int diff = r.countedHeads() - lineHeads(ctx, g, lineId, r.date());
                if (diff == 0) throw ApiException.badRequest("COUNT_MATCHES", "The count matches the record; nothing to change.");
                ids.add(insertMove(ctx, g.id(), r.date(), diff > 0 ? "COUNT_GAIN" : "COUNT_LOSS", Math.abs(diff), null, r.cause(), null, null, null, lineId));
            }
            case "MOVED" -> {
                if (r.toGroupId() == null || r.toGroupId() == g.id()) throw ApiException.badRequest("GROUP_REQUIRED", "Choose the group they moved to.");
                Group to = counted(ctx, r.toGroupId());
                if (!to.species().equals(g.species())) throw ApiException.badRequest("SPECIES_MISMATCH", "Animals can only move to a group of the same species.");
                requireOpen(to, "MOVED_IN");
                int heads = need(r.heads());
                long fromLine = line(ctx, g, r.lineId());
                FlockLine from = lines(ctx.tenantId(), g.id(), r.date()).stream().filter(l -> l.id() == fromLine).findFirst().orElseThrow();
                // They arrive as the same variety in the other group
                long toLine = line(ctx, to, null, from.species(), from.variety() != null ? from.variety() : "");
                long out = insertMove(ctx, g.id(), r.date(), "MOVED_OUT", heads, r.kg(), r.cause(), null, null, null, fromLine);
                long in = insertMove(ctx, to.id(), r.date(), "MOVED_IN", heads, r.kg(), r.cause(), null, null, out, toLine);
                jdbc.sql("update sf_head_movements set pair_id = :in where id = :out and tenant_id = :t").param("in", in).param("out", out).param("t", ctx.tenantId()).update();
                ids.add(out);
            }
            default -> {
                String kind = "RECEIVED".equals(r.kind()) ? "PLACED" : r.kind();
                requireOpen(g, kind);
                ids.add(insertMove(ctx, g.id(), r.date(), kind, need(r.heads()), r.kg(), r.cause(), null, null, null, line(ctx, g, r.lineId(), r.species(), r.variety())));
            }
        }
        requireNonNegative(ctx, g);
        audit.record(ctx.tenantId(), ctx.userId(), "HEADS_RECORDED", "ANIMAL_GROUP", g.id(), Map.of("kind", r.kind(), "date", r.date().toString()));
        return ids.stream().map(id -> movement(ctx, id)).toList();
    }

    private static int need(Integer heads) {
        if (heads == null) throw ApiException.badRequest("HEADS_REQUIRED", "Enter the number of birds or animals.");
        return heads;
    }

    /** Deletes a movement recorded by hand (not one from a sale, purchase or daily record). */
    @Transactional
    public void deleteMovement(TenantContext ctx, long id) {
        Map<String, Object> m = jdbc.sql("select group_id, document_id, batch_day_id, pair_id, kind from sf_head_movements where id = :id and tenant_id = :t")
                .param("id", id).param("t", ctx.tenantId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("MOVEMENT_NOT_FOUND", "Head movement not found."));
        if (m.get("document_id") != null) throw ApiException.conflict("MOVEMENT_HAS_RECORD", "This movement belongs to a sale or purchase; reverse that record instead.");
        if (m.get("batch_day_id") != null) throw ApiException.conflict("MOVEMENT_FROM_DAILY_RECORD", "Change the daily batch record instead.");
        Group g = counted(ctx, ((Number) m.get("group_id")).longValue());
        Object pair = m.get("pair_id");
        jdbc.sql("delete from sf_head_movements where tenant_id = :t and (id = :id or id = :pair)").param("t", ctx.tenantId()).param("id", id).param("pair", pair).update();
        requireNonNegative(ctx, g);
        if (pair != null) requireNonNegative(ctx, counted(ctx, jdbc.sql("select group_id from sf_head_movements where id = :p").param("p", pair)
                .query(Long.class).optional().orElse(g.id())));
        audit.record(ctx.tenantId(), ctx.userId(), "HEADS_DELETED", "ANIMAL_GROUP", g.id(), Map.of("kind", (String) m.get("kind")));
    }

    Movement movement(TenantContext ctx, long id) {
        return jdbc.sql(MOVE_SELECT + " and m.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> movement(rs)).single();
    }

    private static final String MOVE_SELECT = """
            select m.id, m.move_date, m.kind, m.heads, m.kg, m.cause, m.document_id, m.batch_day_id, p.group_id, pco.name,
                   m.document_id is not null and d.status <> 'POSTED' as reversed, m.line_id, fl.species, fl.variety
            from sf_head_movements m
            left join sf_finance_documents d on d.id = m.document_id and d.tenant_id = m.tenant_id
            left join sf_head_movements p on p.id = m.pair_id and p.tenant_id = m.tenant_id
            left join sf_animal_groups pg on pg.id = p.group_id and pg.tenant_id = m.tenant_id
            left join sf_cost_objects pco on pco.id = pg.cost_object_id and pco.tenant_id = m.tenant_id
            left join sf_flock_lines fl on fl.id = m.line_id and fl.tenant_id = m.tenant_id
            where m.tenant_id = :t""";

    private static Movement movement(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Movement(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getInt(4), rs.getBigDecimal(5), rs.getString(6),
                (Long) rs.getObject(7), (Long) rs.getObject(8), (Long) rs.getObject(9), rs.getString(10), rs.getBoolean(11), (Long) rs.getObject(12),
                rs.getString(13), rs.getString(14));
    }

    @Transactional(readOnly = true)
    public List<Movement> movements(TenantContext ctx, long groupId) {
        counted(ctx, groupId);
        return jdbc.sql(MOVE_SELECT + " and m.group_id = :g order by m.move_date desc, m.id desc").param("t", ctx.tenantId()).param("g", groupId)
                .query((rs, i) -> movement(rs)).list();
    }

    // ---- Sales (J6) and births (J10) ---------------------------------------------------------------------------------

    @Transactional
    public Movement sell(TenantContext ctx, long groupId, SaleRequest r, String key) {
        notFuture(r.date());
        Group g = counted(ctx, groupId);
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a sale needs permission to record money.");
        if (r.partyId() == null && r.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose the buyer or the account the money came into.");
        if ("KG".equals(r.rateBasis()) && r.kg() == null) throw ApiException.badRequest("WEIGHT_REQUIRED", "Enter the live weight for a price per kg.");
        BigDecimal quantity = "KG".equals(r.rateBasis()) ? r.kg() : BigDecimal.valueOf(r.heads());
        BigDecimal amount = quantity.multiply(r.rate()).setScale(2, RoundingMode.HALF_UP);
        String description = r.heads() + " sold" + (r.kg() != null ? ", " + r.kg().stripTrailingZeros().toPlainString() + " kg" : "");
        Document sale = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, r.partyId(), g.farmId(),
                r.partyId() == null ? r.financialAccountId() : null,
                List.of(new LineRequest(herd.systemCategory("LIVESTOCK_SALE"), description, quantity, null, null, amount, g.costObjectId(), g.farmId())),
                null, r.referenceNo(), true, r.paidNow()), key);
        long id = insertMove(ctx, g.id(), r.date(), "SOLD", r.heads(), r.kg(), null, sale.summary().id(), null, null, line(ctx, g, r.lineId()));
        requireNonNegative(ctx, g);
        audit.record(ctx.tenantId(), ctx.userId(), "HEADS_RECORDED", "ANIMAL_GROUP", g.id(), Map.of("kind", "SOLD", "heads", r.heads()));
        return movement(ctx, id);
    }

    @Transactional
    public LivestockDtos.Event births(TenantContext ctx, long groupId, BirthRequest r, EventService events) {
        notFuture(r.date());
        Group g = counted(ctx, groupId);
        if ("CHICKEN".equals(g.species())) throw ApiException.badRequest("NOT_FOR_POULTRY", "Kidding and lambing are for goats and sheep.");
        int dead = r.dead() == null ? 0 : r.dead();
        if (r.alive() == 0 && dead == 0) throw ApiException.badRequest("HEADS_REQUIRED", "Enter the number born.");
        Long damId = null;
        if (r.damId() != null) {
            AnimalRow dam = herd.animalRow(ctx.tenantId(), r.damId());
            if (!"FEMALE".equals(dam.sex()) || !dam.species().equals(g.species())) throw ApiException.badRequest("INVALID_DAM", "The dam must be a female of the same species.");
            damId = dam.id();
        }
        if (r.alive() > 0) insertMove(ctx, g.id(), r.date(), "BORN", r.alive(), null, null, null, null, null, line(ctx, g, null));
        LivestockDtos.Event e = events.insert(ctx, damId, g.id(), "KIDDING", r.date(), r.notes(), null, null);
        jdbc.sql("update sf_animal_events set born_alive = :a, born_dead = :d where id = :id and tenant_id = :t")
                .param("a", r.alive()).param("d", dead).param("id", e.id()).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "HEADS_RECORDED", "ANIMAL_GROUP", g.id(), Map.of("kind", "BORN", "heads", r.alive()));
        return events.event(ctx, e.id());
    }

    // ---- Daily batch record (J4, J5, J13) -------------------------------------------------------------------------------

    @Transactional
    public BatchDay day(TenantContext ctx, long groupId, LocalDate date) {
        Group g = counted(ctx, groupId);
        return toDay(ctx, g, date);
    }

    private BatchDay toDay(TenantContext ctx, Group g, LocalDate date) {
        Map<String, Object> row = jdbc.sql("select * from sf_batch_days where tenant_id = :t and group_id = :g and day = :d")
                .param("t", ctx.tenantId()).param("g", g.id()).param("d", date).query().listOfRows().stream().findFirst().orElse(Map.of());
        int heads = heads(ctx.tenantId(), g.id(), date);
        List<String> warnings = new ArrayList<>();
        if (withheld(ctx.tenantId(), g.id(), date)) warnings.add("EGGS_WITHHELD");
        int deaths = intOf(row.get("deaths"));
        if (deaths > 0 && deaths * 100 > (heads + deaths + intOf(row.get("culls")))) warnings.add("HIGH_MORTALITY");
        Long dayId = longOf(row.get("id"));
        List<DayLineView> byLine = dayLines(ctx.tenantId(), g, dayId, date);
        return new BatchDay(date, deaths, (String) row.get("death_cause"), intOf(row.get("culls")), intOf(row.get("eggs_good")), intOf(row.get("eggs_cracked")),
                intOf(row.get("eggs_broken")), longOf(row.get("feed_item_id")), (BigDecimal) row.get("feed_kg"), longOf(row.get("feed_document_id")),
                (Integer) row.get("weight_birds"), (BigDecimal) row.get("weight_kg"), (String) row.get("notes"), heads, warnings, dayVersion(row, byLine), byLine);
    }

    /** Every variety of the group on a day, with what was recorded for it (zeros when nothing was). */
    private List<DayLineView> dayLines(long tenantId, Group g, Long dayId, LocalDate date) {
        Map<Long, Map<String, Object>> saved = new HashMap<>();
        if (dayId != null) {
            jdbc.sql("select line_id, deaths, culls, eggs, egg_rate from sf_batch_day_lines where tenant_id = :t and batch_day_id = :d")
                    .param("t", tenantId).param("d", dayId).query().listOfRows().forEach(m -> saved.put(((Number) m.get("line_id")).longValue(), m));
        }
        // The last eggs and rate before this day, which the form offers for the new day
        Map<Long, Map<String, Object>> lastEggs = new HashMap<>(), lastRate = new HashMap<>();
        jdbc.sql("""
                        select distinct on (dl.line_id) dl.line_id, d.day, dl.eggs from sf_batch_day_lines dl
                        join sf_batch_days d on d.id = dl.batch_day_id and d.tenant_id = dl.tenant_id
                        where dl.tenant_id = :t and d.group_id = :g and d.day < :date and dl.eggs > 0 order by dl.line_id, d.day desc""")
                .param("t", tenantId).param("g", g.id()).param("date", date).query().listOfRows().forEach(m -> lastEggs.put(((Number) m.get("line_id")).longValue(), m));
        jdbc.sql("""
                        select distinct on (dl.line_id) dl.line_id, dl.egg_rate from sf_batch_day_lines dl
                        join sf_batch_days d on d.id = dl.batch_day_id and d.tenant_id = dl.tenant_id
                        where dl.tenant_id = :t and d.group_id = :g and d.day < :date and dl.egg_rate is not null order by dl.line_id, d.day desc""")
                .param("t", tenantId).param("g", g.id()).param("date", date).query().listOfRows().forEach(m -> lastRate.put(((Number) m.get("line_id")).longValue(), m));
        return lines(tenantId, g.id(), date).stream().map(l -> {
            Map<String, Object> m = saved.getOrDefault(l.id(), Map.of());
            Map<String, Object> eggs = lastEggs.get(l.id());
            return new DayLineView(l.id(), l.species(), l.variety(), l.heads(), intOf(m.get("deaths")), intOf(m.get("culls")), intOf(m.get("eggs")), (BigDecimal) m.get("egg_rate"),
                    eggs == null ? null : Integer.valueOf(intOf(eggs.get("eggs"))), eggs == null ? null : ((java.sql.Date) eggs.get("day")).toLocalDate(),
                    lastRate.containsKey(l.id()) ? (BigDecimal) lastRate.get(l.id()).get("egg_rate") : null);
        }).toList();
    }

    /** The version of a batch day: a hash of what the user recorded, not of derived figures (mobile offline design, N7). */
    private static String dayVersion(Map<String, Object> row, List<DayLineView> byLine) {
        if (row.isEmpty()) return Versions.NONE;
        // A day recorded by variety also changes with its varieties' figures; a day without them keeps its earlier version
        String varieties = byLine.stream().filter(l -> l.deaths() > 0 || l.culls() > 0 || l.eggs() > 0 || l.eggRate() != null)
                .map(l -> l.lineId() + ":" + l.deaths() + ":" + l.culls() + ":" + l.eggs() + ":" + (l.eggRate() == null ? "" : l.eggRate().stripTrailingZeros().toPlainString()))
                .reduce((a, b) -> a + "," + b).orElse(null);
        return varieties == null
                ? Versions.token(row.get("deaths"), row.get("death_cause"), row.get("culls"), row.get("eggs_good"), row.get("eggs_cracked"), row.get("eggs_broken"),
                        row.get("feed_item_id"), row.get("feed_kg"), row.get("weight_birds"), row.get("weight_kg"), row.get("notes"))
                : Versions.token(row.get("deaths"), row.get("death_cause"), row.get("culls"), row.get("eggs_good"), row.get("eggs_cracked"), row.get("eggs_broken"),
                        row.get("feed_item_id"), row.get("feed_kg"), row.get("weight_birds"), row.get("weight_kg"), row.get("notes"), varieties);
    }

    private static int intOf(Object o) { return o == null ? 0 : ((Number) o).intValue(); }

    private static Long longOf(Object o) { return o == null ? null : ((Number) o).longValue(); }

    /** A withdrawal on the batch (a group health event) covers its eggs (J9). */
    boolean withheld(long tenantId, long groupId, LocalDate date) {
        return jdbc.sql("select exists (select 1 from sf_animal_events where tenant_id = :t and group_id = :g and event_date <= :d and withdrawal_until >= :d)")
                .param("t", tenantId).param("g", groupId).param("d", date).query(Boolean.class).single();
    }

    @Transactional
    public BatchDay saveDay(TenantContext ctx, long groupId, LocalDate date, BatchDayRequest r) {
        notFuture(date);
        Group g = counted(ctx, groupId);
        if (!"CHICKEN".equals(g.species())) throw ApiException.badRequest("NOT_A_BATCH", "Daily records are kept for poultry batches.");
        if (r.baseVersion() != null) {
            // One save of a batch day at a time, so the version check and the write see the same day (N7)
            jdbc.sql("select pg_advisory_xact_lock(hashtext(:k))").param("k", "batchday:" + ctx.tenantId() + ":" + g.id() + ":" + date).query().listOfRows();
            Map<String, Object> stored = jdbc.sql("select * from sf_batch_days where tenant_id = :t and group_id = :g and day = :d")
                    .param("t", ctx.tenantId()).param("g", g.id()).param("d", date).query().listOfRows().stream().findFirst().orElse(Map.of());
            Versions.requireCurrent(r.baseVersion(), dayVersion(stored, dayLines(ctx.tenantId(), g, longOf(stored.get("id")), date)));
        }
        if (g.placedOn() != null && date.isBefore(g.placedOn())) throw ApiException.badRequest("BEFORE_PLACEMENT", "The batch was placed after this date.");
        if ((r.feedItemId() == null) != (r.feedKg() == null)) throw ApiException.badRequest("FEED_ITEM_AND_KG", "Choose the feed and enter the kg.");
        if ((r.weightBirds() == null) != (r.weightKg() == null)) throw ApiException.badRequest("WEIGHT_SAMPLE", "Enter the birds weighed and their total kg.");
        int deaths = r.deaths() == null ? 0 : r.deaths(), culls = r.culls() == null ? 0 : r.culls();
        int good = nz(r.eggsGood()), cracked = nz(r.eggsCracked()), broken = nz(r.eggsBroken());
        List<DayLine> byLine = r.lines() == null ? List.of() : r.lines();
        Set<Long> seen = new HashSet<>();
        for (DayLine l : byLine) {
            line(ctx, g, l.lineId());
            if (!seen.add(l.lineId())) throw ApiException.badRequest("LINE_TWICE", "Each variety appears once in a day.");
        }
        if (!byLine.isEmpty()) {
            // By variety, the day's deaths, culls and eggs are the varieties' sums; good eggs are those laid less the cracked and broken
            deaths = byLine.stream().mapToInt(l -> nz(l.deaths())).sum();
            culls = byLine.stream().mapToInt(l -> nz(l.culls())).sum();
            int laid = byLine.stream().mapToInt(l -> nz(l.eggs())).sum();
            good = laid - cracked - broken;
            if (good < 0) throw ApiException.badRequest("EGGS_SPLIT", "Cracked and broken eggs cannot be more than the eggs laid.");
        }
        if ("BROILER".equals(g.purpose()) && good + cracked + broken > 0) throw ApiException.badRequest("NO_EGGS_FOR_BROILERS", "Broiler batches do not lay eggs.");
        Optional<Map<String, Object>> existing = jdbc.sql("select id, feed_item_id, feed_kg, feed_document_id from sf_batch_days where tenant_id = :t and group_id = :g and day = :d")
                .param("t", ctx.tenantId()).param("g", g.id()).param("d", date).query().listOfRows().stream().findFirst();
        long dayId = existing.map(m -> ((Number) m.get("id")).longValue()).orElseGet(() -> jdbc.sql(
                        "insert into sf_batch_days (tenant_id, group_id, day, created_by) values (:t, :g, :d, :u) returning id")
                .param("t", ctx.tenantId()).param("g", g.id()).param("d", date).param("u", ctx.userId()).query(Long.class).single());
        jdbc.sql("""
                        update sf_batch_days set deaths = :deaths, death_cause = :cause, culls = :culls, eggs_good = :good, eggs_cracked = :cracked,
                          eggs_broken = :broken, weight_birds = :wb, weight_kg = :wkg, notes = :notes, updated_at = now() where id = :id and tenant_id = :t""")
                .param("deaths", deaths).param("cause", HerdService.trim(r.deathCause())).param("culls", culls).param("good", good).param("cracked", cracked)
                .param("broken", broken).param("wb", r.weightBirds()).param("wkg", r.weightKg()).param("notes", HerdService.trim(r.notes()))
                .param("id", dayId).param("t", ctx.tenantId()).update();
        // Deaths and culls are the day's head movements (J7)
        jdbc.sql("delete from sf_head_movements where tenant_id = :t and batch_day_id = :d").param("t", ctx.tenantId()).param("d", dayId).update();
        jdbc.sql("delete from sf_batch_day_lines where tenant_id = :t and batch_day_id = :d").param("t", ctx.tenantId()).param("d", dayId).update();
        if (byLine.isEmpty()) {
            if (deaths > 0) insertMove(ctx, g.id(), date, "DIED", deaths, null, r.deathCause(), null, dayId, null, line(ctx, g, null));
            if (culls > 0) insertMove(ctx, g.id(), date, "CULLED", culls, null, null, null, dayId, null, line(ctx, g, null));
        } else {
            for (DayLine l : byLine) {
                if (nz(l.deaths()) > 0) insertMove(ctx, g.id(), date, "DIED", l.deaths(), null, r.deathCause(), null, dayId, null, l.lineId());
                if (nz(l.culls()) > 0) insertMove(ctx, g.id(), date, "CULLED", l.culls(), null, null, null, dayId, null, l.lineId());
                if (nz(l.deaths()) + nz(l.culls()) + nz(l.eggs()) > 0 || l.eggRate() != null) {
                    jdbc.sql("""
                                    insert into sf_batch_day_lines (tenant_id, batch_day_id, line_id, deaths, culls, eggs, egg_rate)
                                    values (:t, :d, :l, :deaths, :culls, :eggs, :rate)""")
                            .param("t", ctx.tenantId()).param("d", dayId).param("l", l.lineId()).param("deaths", nz(l.deaths())).param("culls", nz(l.culls()))
                            .param("eggs", nz(l.eggs())).param("rate", l.eggRate()).update();
                }
            }
        }
        requireNonNegative(ctx, g);
        // Feed: one stock use to the batch for the day (J13); a change reverses the earlier use
        Long oldItem = existing.map(m -> longOf(m.get("feed_item_id"))).orElse(null);
        BigDecimal oldKg = existing.map(m -> (BigDecimal) m.get("feed_kg")).orElse(null);
        Long oldDoc = existing.map(m -> longOf(m.get("feed_document_id"))).orElse(null);
        boolean feedChanged = !Objects.equals(oldItem, r.feedItemId()) || (oldKg == null ? r.feedKg() != null : r.feedKg() == null || oldKg.compareTo(r.feedKg()) != 0);
        if (feedChanged) {
            if (oldDoc != null) documents.reverse(ctx, oldDoc, null);
            Long newDoc = null;
            if (r.feedItemId() != null) {
                newDoc = stock.issue(ctx, new StockDtos.IssueRequest(date, null, g.costObjectId(), g.farmId(),
                        List.of(new StockDtos.ItemQuantity(r.feedItemId(), r.feedKg(), "kg", null, null)), "Feed, " + g.name()), null).document().summary().id();
            }
            jdbc.sql("update sf_batch_days set feed_item_id = :i, feed_kg = :kg, feed_document_id = :doc where id = :id and tenant_id = :t")
                    .param("i", r.feedItemId()).param("kg", r.feedKg()).param("doc", newDoc).param("id", dayId).param("t", ctx.tenantId()).update();
        }
        // Good and cracked eggs into stock (J5); broken eggs are counted but not stocked
        repostEggs(ctx, g, date, good + cracked);
        audit.record(ctx.tenantId(), ctx.userId(), "BATCH_DAY_RECORDED", "ANIMAL_GROUP", g.id(), Map.of("day", date.toString()));
        return toDay(ctx, herd.group(ctx, g.id()), date);
    }

    private static int nz(Integer v) { return v == null ? 0 : v; }

    /** The tenant's Eggs produce item (pieces, nil value, Egg sales), created or adopted on first use. */
    @Transactional
    public long eggItem(TenantContext ctx) {
        Optional<Long> set = jdbc.sql("select egg_item_id from sf_livestock_settings where tenant_id = :t and egg_item_id is not null")
                .param("t", ctx.tenantId()).query(Long.class).optional();
        if (set.isPresent()) return set.get();
        long id = jdbc.sql("select id from sf_stock_items where tenant_id = :t and lower(name) = 'eggs' and kind = 'PRODUCE' and base_unit_code = 'piece'")
                .param("t", ctx.tenantId()).query(Long.class).optional()
                .orElseGet(() -> {
                    if (jdbc.sql("select exists (select 1 from sf_stock_items where tenant_id = :t and lower(name) = 'eggs')").param("t", ctx.tenantId())
                            .query(Boolean.class).single()) {
                        throw ApiException.conflict("EGG_ITEM_CONFLICT", "A stock item named Eggs exists but is not farm produce in pieces. Rename it first.");
                    }
                    return items.createItem(ctx, new StockDtos.ItemRequest("Eggs", "PRODUCE", "piece", null, herd.systemCategory("EGG_SALE"),
                            BigDecimal.ZERO, false, "Eggs from the batches (livestock records).", null)).id();
                });
        jdbc.sql("""
                        insert into sf_livestock_settings (tenant_id, egg_item_id) values (:t, :i)
                        on conflict (tenant_id) do update set egg_item_id = excluded.egg_item_id""")
                .param("t", ctx.tenantId()).param("i", id).update();
        return id;
    }

    /** Keeps the day's egg produce records equal to the day's good and cracked eggs, as for milk (I6). */
    private void repostEggs(TenantContext ctx, Group g, LocalDate date, int target) {
        List<Map<String, Object>> posted = jdbc.sql("""
                        select p.document_id, p.eggs from sf_egg_postings p join sf_finance_documents d on d.id = p.document_id and d.tenant_id = p.tenant_id
                        where p.tenant_id = :t and p.day = :d and p.group_id = :g and d.status = 'POSTED' order by p.id desc""")
                .param("t", ctx.tenantId()).param("d", date).param("g", g.id()).query().listOfRows();
        int current = posted.stream().mapToInt(m -> ((Number) m.get("eggs")).intValue()).sum();
        if (target == current) return;
        if (target > current) { produceEggs(ctx, g, date, target - current); return; }
        for (Map<String, Object> p : posted) {
            try {
                documents.reverse(ctx, ((Number) p.get("document_id")).longValue(), null);
            } catch (ApiException e) {
                throw ApiException.conflict("EGGS_ALREADY_USED", "Some of this day's eggs are already sold or used. Record the difference as damaged eggs instead.");
            }
        }
        if (target > 0) produceEggs(ctx, g, date, target);
    }

    private void produceEggs(TenantContext ctx, Group g, LocalDate date, int eggs) {
        Document doc = stock.produce(ctx, new StockDtos.ProduceRequest(date, null, g.costObjectId(), g.farmId(),
                List.of(new StockDtos.ItemQuantity(eggItem(ctx), BigDecimal.valueOf(eggs), null, null, null)), "Eggs, " + g.name()), null).document();
        jdbc.sql("insert into sf_egg_postings (tenant_id, day, group_id, document_id, eggs) values (:t, :d, :g, :doc, :e)")
                .param("t", ctx.tenantId()).param("d", date).param("g", g.id()).param("doc", doc.summary().id()).param("e", eggs).update();
    }

    @Transactional
    public List<RecordRef> eggUses(TenantContext ctx, EggUseRequest r, String key) {
        notFuture(r.date());
        long eggs = eggItem(ctx);
        List<RecordRef> out = new ArrayList<>();
        if (nz(r.homeUse()) > 0) {
            out.add(ref(stock.issue(ctx, new StockDtos.IssueRequest(r.date(), null, null, null,
                    List.of(new StockDtos.ItemQuantity(eggs, BigDecimal.valueOf(r.homeUse()), null, null, null)), "Eggs for home use"), key == null ? null : key + ":home").document()));
        }
        if (nz(r.damaged()) > 0) {
            String reason = HerdService.trim(r.reason());
            if (reason == null) throw ApiException.badRequest("REASON_REQUIRED", "Say why the eggs were damaged or thrown away.");
            out.add(ref(stock.wastage(ctx, new StockDtos.WastageRequest(r.date(), null, reason,
                    List.of(new StockDtos.ItemQuantity(eggs, BigDecimal.valueOf(r.damaged()), null, null, null))), key == null ? null : key + ":damaged").document()));
        }
        if (out.isEmpty()) throw ApiException.badRequest("NOTHING_TO_RECORD", "Enter the number of eggs.");
        audit.record(ctx.tenantId(), ctx.userId(), "EGG_USE_RECORDED", "EGGS", r.date().toString(), Map.of("records", out.size()));
        return out;
    }

    @Transactional
    public RecordRef eggSale(TenantContext ctx, EggSaleRequest r, String key) {
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a sale needs permission to record money.");
        if (r.partyId() == null && r.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose the buyer or the account the money came into.");
        Group g = counted(ctx, r.groupId());
        Document sale = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, r.partyId(), g.farmId(),
                r.partyId() == null ? r.financialAccountId() : null,
                List.of(new LineRequest(herd.systemCategory("EGG_SALE"), "Eggs", r.quantity(), r.unit(), null, r.amount(), g.costObjectId(), g.farmId(),
                        eggItem(ctx), null, null, null, null)), null, r.referenceNo(), true, r.paidNow()), key);
        return ref(sale);
    }

    private static RecordRef ref(Document d) {
        return new RecordRef(d.summary().id(), d.summary().number(), d.summary().type(), d.summary().totalAmount());
    }

    // ---- Health plans (J9) ------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PlanItem> plan(TenantContext ctx, long groupId) {
        counted(ctx, groupId);
        return jdbc.sql("select id, kind, description, due_on, done_event_id from sf_health_plan_items where tenant_id = :t and group_id = :g order by due_on, id")
                .param("t", ctx.tenantId()).param("g", groupId)
                .query((rs, i) -> new PlanItem(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class), (Long) rs.getObject(5))).list();
    }

    @Transactional
    public PlanItem addPlanItem(TenantContext ctx, long groupId, PlanItemRequest r) {
        counted(ctx, groupId);
        long id = jdbc.sql("insert into sf_health_plan_items (tenant_id, group_id, kind, description, due_on, created_by) values (:t, :g, :k, :d, :due, :u) returning id")
                .param("t", ctx.tenantId()).param("g", groupId).param("k", r.kind()).param("d", r.description().trim()).param("due", r.dueOn())
                .param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "HEALTH_PLAN_SAVED", "ANIMAL_GROUP", groupId, Map.of("items", 1));
        return plan(ctx, groupId).stream().filter(p -> p.id() == id).findFirst().orElseThrow();
    }

    /**
     * Copies an earlier batch's health record and plan to this one, with dates shifted by the difference in placement (J9).
     * The result is the farmer's own plan; nothing is suggested.
     */
    @Transactional
    public List<PlanItem> copyPlan(TenantContext ctx, long groupId, CopyPlanRequest r) {
        Group to = counted(ctx, groupId), from = counted(ctx, r.fromGroupId());
        if (!to.species().equals(from.species())) throw ApiException.badRequest("SPECIES_MISMATCH", "Copy a plan from a group of the same species.");
        if (to.placedOn() == null || from.placedOn() == null) throw ApiException.badRequest("PLACEMENT_REQUIRED", "Both batches need a placement date.");
        long shift = java.time.temporal.ChronoUnit.DAYS.between(from.placedOn(), to.placedOn())
                + nz(from.ageAtPlacementDays()) - nz(to.ageAtPlacementDays());
        List<Map<String, Object>> source = new ArrayList<>(jdbc.sql("""
                        select kind, description, event_date as due from sf_animal_events where tenant_id = :t and group_id = :g
                          and kind in ('TREATMENT', 'VACCINATION', 'DEWORMING', 'HEALTH_OTHER')
                        union all
                        select kind, description, due_on from sf_health_plan_items where tenant_id = :t and group_id = :g and done_event_id is null""")
                .param("t", ctx.tenantId()).param("g", from.id()).query().listOfRows());
        if (source.isEmpty()) throw ApiException.badRequest("NOTHING_TO_COPY", "The other batch has no health records to copy.");
        for (Map<String, Object> s : source) {
            LocalDate due = ((java.sql.Date) s.get("due")).toLocalDate().plusDays(shift);
            jdbc.sql("insert into sf_health_plan_items (tenant_id, group_id, kind, description, due_on, created_by) values (:t, :g, :k, :d, :due, :u)")
                    .param("t", ctx.tenantId()).param("g", to.id()).param("k", s.get("kind")).param("d", s.get("description") == null ? "" : s.get("description"))
                    .param("due", due).param("u", ctx.userId()).update();
        }
        audit.record(ctx.tenantId(), ctx.userId(), "HEALTH_PLAN_SAVED", "ANIMAL_GROUP", to.id(), Map.of("copiedFrom", from.name(), "items", source.size()));
        return plan(ctx, to.id());
    }
}
