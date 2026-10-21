package com.smartfarm360.livestock;

import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.livestock.HerdService.AnimalRow;
import com.smartfarm360.livestock.LivestockDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Health and breeding events (livestock design, section 5): treatments with milk withdrawal and next-due dates (I9),
 * inseminations with worked-out dates (I10), and calvings that create calves and lactations (I11). The app records what
 * was done; it never diagnoses or advises (principle 5).
 */
@Service
public class EventService {
    /** Standard periods (I10); the farmer can override every worked-out date. */
    static final int CHECK_FROM_DAYS = 60, DRY_OFF_BEFORE_DAYS = 60;

    static int gestationDays(String species) { return "BUFFALO".equals(species) ? 310 : 283; }

    private final JdbcClient jdbc;
    private final HerdService herd;
    private final DocumentService documents;
    private final StockRecordService stock;
    private final AuditService audit;

    public EventService(JdbcClient jdbc, HerdService herd, DocumentService documents, StockRecordService stock, AuditService audit) {
        this.jdbc = jdbc;
        this.herd = herd;
        this.documents = documents;
        this.stock = stock;
        this.audit = audit;
    }

    private static final String EVENT_SELECT = """
            select e.id, e.animal_id, a.tag, e.group_id, co.name, e.kind, e.event_date, e.description, e.result, e.service, e.bull, e.done_by,
                   e.withdrawal_until, e.next_due, e.check_due_from, e.expected_calving, e.dry_off_due, e.document_id, e.stock_document_id
            from sf_animal_events e
            left join sf_animals a on a.id = e.animal_id and a.tenant_id = e.tenant_id
            left join sf_animal_groups g on g.id = e.group_id and g.tenant_id = e.tenant_id
            left join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = e.tenant_id
            where e.tenant_id = :t""";

    private static Event event(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Event(rs.getLong(1), (Long) rs.getObject(2), rs.getString(3), (Long) rs.getObject(4), rs.getString(5), rs.getString(6),
                rs.getObject(7, LocalDate.class), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12),
                rs.getObject(13, LocalDate.class), rs.getObject(14, LocalDate.class), rs.getObject(15, LocalDate.class), rs.getObject(16, LocalDate.class),
                rs.getObject(17, LocalDate.class), (Long) rs.getObject(18), (Long) rs.getObject(19));
    }

    @Transactional(readOnly = true)
    public List<Event> events(TenantContext ctx, Long animalId, LocalDate from, LocalDate to) {
        return jdbc.sql(EVENT_SELECT + """
                         and (cast(:a as bigint) is null or e.animal_id = :a
                              or (e.animal_id is null and e.group_id in (select m.group_id from sf_animal_group_moves m where m.tenant_id = :t and m.animal_id = :a)))
                          and (cast(:from as date) is null or e.event_date >= :from) and (cast(:to as date) is null or e.event_date <= :to)
                        order by e.event_date desc, e.id desc""")
                .param("t", ctx.tenantId()).param("a", animalId).param("from", from).param("to", to).query((rs, i) -> event(rs)).list();
    }

    Event event(TenantContext ctx, long id) {
        return jdbc.sql(EVENT_SELECT + " and e.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> event(rs)).single();
    }

    Event insert(TenantContext ctx, Long animalId, Long groupId, String kind, LocalDate date, String description, Long documentId, Long stockDocumentId) {
        long id = jdbc.sql("""
                        insert into sf_animal_events (tenant_id, animal_id, group_id, kind, event_date, description, document_id, stock_document_id, created_by)
                        values (:t, :a, :g, :k, :d, :desc, :doc, :sdoc, :u) returning id""")
                .param("t", ctx.tenantId()).param("a", animalId).param("g", groupId).param("k", kind).param("d", date).param("desc", HerdService.trim(description))
                .param("doc", documentId).param("sdoc", stockDocumentId).param("u", ctx.userId()).query(Long.class).single();
        return event(ctx, id);
    }

    private static void notFuture(LocalDate date) {
        if (date.isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
    }

    // ---- Health (I9) -------------------------------------------------------------------------------------------------

    @Transactional
    public Event health(TenantContext ctx, HealthRequest r, String key) {
        notFuture(r.date());
        if ((r.animalId() == null) == (r.groupId() == null)) throw ApiException.badRequest("ANIMAL_OR_GROUP", "Choose one animal or one group.");
        Long groupId = r.groupId();
        String target;
        if (r.animalId() != null) {
            AnimalRow a = herd.animalRow(ctx.tenantId(), r.animalId());
            if (!a.active()) throw ApiException.conflict("ANIMAL_NOT_ACTIVE", "This animal has left the herd.");
            groupId = herd.groupOn(ctx.tenantId(), a.id(), r.date()).orElseThrow(() -> ApiException.badRequest("NOT_IN_HERD", "The animal was not here on that date."));
            target = a.tag();
        } else {
            target = herd.group(ctx, groupId).name();
        }
        long groupCo = herd.group(ctx, groupId).costObjectId();
        Long stockDoc = null, billDoc = null;
        if (r.medicine() != null) {
            stockDoc = stock.issue(ctx, new StockDtos.IssueRequest(r.date(), null, groupCo, null,
                    List.of(new StockDtos.ItemQuantity(r.medicine().itemId(), r.medicine().quantity(), r.medicine().unit(), null, null)),
                    r.description() + " (" + target + ")"), key == null ? null : key + ":medicine").document().summary().id();
        }
        if (r.bill() != null) billDoc = vetBill(ctx, r.bill(), r.date(), groupCo, r.description() + " (" + target + ")", key == null ? null : key + ":bill");
        LocalDate withdrawal = r.withdrawalDays() == null || r.withdrawalDays() == 0 ? null : r.date().plusDays(r.withdrawalDays() - 1L);
        if (r.nextDue() != null && !r.nextDue().isAfter(r.date())) throw ApiException.badRequest("INVALID_NEXT_DUE", "The next due date must be after this one.");
        long id = jdbc.sql("""
                        insert into sf_animal_events (tenant_id, animal_id, group_id, kind, event_date, description, done_by, withdrawal_until, next_due,
                          document_id, stock_document_id, created_by)
                        values (:t, :a, :g, :k, :d, :desc, :by, :w, :next, :doc, :sdoc, :u) returning id""")
                .param("t", ctx.tenantId()).param("a", r.animalId()).param("g", r.animalId() == null ? groupId : null).param("k", r.kind())
                .param("d", r.date()).param("desc", r.description().trim()).param("by", HerdService.trim(r.doneBy())).param("w", withdrawal)
                .param("next", r.nextDue()).param("doc", billDoc).param("sdoc", stockDoc).param("u", ctx.userId()).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_HEALTH_RECORDED", "ANIMAL_EVENT", id, Map.of("kind", r.kind(), "for", target));
        return event(ctx, id);
    }

    private long vetBill(TenantContext ctx, VetBill b, LocalDate date, long groupCo, String description, String key) {
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a bill needs permission to record money.");
        if (b.partyId() == null && b.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose who is paid or the account it is paid from.");
        return documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(date, null, b.partyId(), null, b.partyId() == null ? b.financialAccountId() : null,
                List.of(new LineRequest(herd.systemCategory("VETERINARY"), description, null, null, null, b.amount(), groupCo, null)), null, null, true, null),
                key).summary().id();
    }

    // ---- Breeding (I10) ----------------------------------------------------------------------------------------------

    @Transactional
    public Event breeding(TenantContext ctx, long animalId, BreedingRequest r, String key) {
        notFuture(r.date());
        AnimalRow a = herd.animalRow(ctx.tenantId(), animalId);
        if (!a.active()) throw ApiException.conflict("ANIMAL_NOT_ACTIVE", "This animal has left the herd.");
        if (!"FEMALE".equals(a.sex())) throw ApiException.badRequest("FEMALES_ONLY", "Breeding events are recorded for females.");
        if (r.date().isBefore(a.arrivedOn())) throw ApiException.badRequest("NOT_IN_HERD", "The animal was not here on that date.");
        LocalDate check = null, calving = null, dryOff = null;
        String result = null;
        switch (r.kind()) {
            case "INSEMINATION" -> {
                if (r.service() == null) throw ApiException.badRequest("SERVICE_REQUIRED", "Choose AI or natural service.");
                check = r.checkDueFrom() != null ? r.checkDueFrom() : r.date().plusDays(CHECK_FROM_DAYS);
                calving = r.expectedCalving() != null ? r.expectedCalving() : r.date().plusDays(gestationDays(a.species()));
                dryOff = r.dryOffDue() != null ? r.dryOffDue() : calving.minusDays(DRY_OFF_BEFORE_DAYS);
                if (!calving.isAfter(r.date()) || dryOff.isAfter(calving)) throw ApiException.badRequest("INVALID_BREEDING_DATES", "Check the expected dates.");
            }
            case "PREGNANCY_CHECK" -> {
                if (r.result() == null) throw ApiException.badRequest("RESULT_REQUIRED", "Choose positive or negative.");
                result = r.result();
                Breeding b = herd.breeding(ctx.tenantId(), a, r.date());
                if (b.lastInsemination() == null) throw ApiException.conflict("NO_INSEMINATION", "Record the insemination before a pregnancy check.");
            }
            case "DRY_OFF" -> {
                Breeding b = herd.breeding(ctx.tenantId(), a, r.date());
                if (!b.inMilk()) throw ApiException.conflict("NOT_IN_MILK", "This animal is not in milk.");
            }
            default -> { }
        }
        Long billDoc = r.bill() == null ? null : vetBill(ctx, r.bill(), r.date(),
                herd.group(ctx, herd.groupOn(ctx.tenantId(), a.id(), r.date()).orElseThrow()).costObjectId(),
                ("INSEMINATION".equals(r.kind()) ? "Insemination" : "Breeding") + " (" + a.tag() + ")", key == null ? null : key + ":bill");
        long id = jdbc.sql("""
                        insert into sf_animal_events (tenant_id, animal_id, kind, event_date, description, result, service, bull, done_by, check_due_from,
                          expected_calving, dry_off_due, document_id, created_by)
                        values (:t, :a, :k, :d, :desc, :res, :svc, :bull, :by, :check, :calving, :dry, :doc, :u) returning id""")
                .param("t", ctx.tenantId()).param("a", a.id()).param("k", r.kind()).param("d", r.date()).param("desc", HerdService.trim(r.notes()))
                .param("res", result).param("svc", "INSEMINATION".equals(r.kind()) ? r.service() : null).param("bull", HerdService.trim(r.bull()))
                .param("by", HerdService.trim(r.doneBy())).param("check", check).param("calving", calving).param("dry", dryOff).param("doc", billDoc)
                .param("u", ctx.userId()).query(Long.class).single();
        if ("DRY_OFF".equals(r.kind())) {
            jdbc.sql("update sf_lactations set dry_off_date = :d where tenant_id = :t and animal_id = :a and dry_off_date is null")
                    .param("d", r.date()).param("t", ctx.tenantId()).param("a", a.id()).update();
            Long target = r.groupId() != null ? r.groupId() : herd.groupFor(ctx, "DRY").map(Group::id).orElse(null);
            if (target != null) herd.moveTo(ctx, a, target, r.date());
        }
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_BREEDING_RECORDED", "ANIMAL_EVENT", id, Map.of("kind", r.kind(), "animal", a.tag()));
        return event(ctx, id);
    }

    // ---- Calving and lactation (I11) -----------------------------------------------------------------------------------

    @Transactional
    public AnimalCard calving(TenantContext ctx, long animalId, CalvingRequest r, LivestockReports reports) {
        notFuture(r.date());
        AnimalRow cow = herd.animalRow(ctx.tenantId(), animalId);
        if (!cow.active()) throw ApiException.conflict("ANIMAL_NOT_ACTIVE", "This animal has left the herd.");
        if (!"FEMALE".equals(cow.sex())) throw ApiException.badRequest("FEMALES_ONLY", "Only a female can calve.");
        List<CalfEntry> calves = r.calves() == null ? List.of() : r.calves();
        if ("LIVE".equals(r.result()) && calves.isEmpty()) throw ApiException.badRequest("CALF_REQUIRED", "Enter the calf's tag.");
        if ("STILLBORN".equals(r.result()) && !calves.isEmpty()) throw ApiException.badRequest("NO_LIVE_CALVES", "A stillbirth has no live calves to add.");
        Optional<Map<String, Object>> last = jdbc.sql("select number, calving_date from sf_lactations where tenant_id = :t and animal_id = :a order by number desc limit 1")
                .param("t", ctx.tenantId()).param("a", cow.id()).query().listOfRows().stream().findFirst();
        if (last.isPresent() && !((java.sql.Date) last.get().get("calving_date")).toLocalDate().isBefore(r.date().minusDays(200))) {
            throw ApiException.conflict("CALVING_TOO_SOON", "This animal calved less than 200 days before this date.");
        }
        String sire = jdbc.sql("""
                        select bull from sf_animal_events where tenant_id = :t and animal_id = :a and kind = 'INSEMINATION' and event_date <= :d
                        order by event_date desc, id desc limit 1""")
                .param("t", ctx.tenantId()).param("a", cow.id()).param("d", r.date()).query(String.class).optional().orElse(null);
        Event e = insert(ctx, cow.id(), null, "CALVING", r.date(), r.notes(), null, null);
        jdbc.sql("update sf_animal_events set result = :r, bull = :b where id = :id and tenant_id = :t")
                .param("r", r.result()).param("b", sire).param("id", e.id()).param("t", ctx.tenantId()).update();
        if (!calves.isEmpty()) {
            Long calfGroup = r.calfGroupId() != null ? r.calfGroupId() : herd.groupFor(ctx, "CALF").map(Group::id)
                    .orElseThrow(() -> ApiException.badRequest("CALF_GROUP_REQUIRED", "Create a Calves group, or choose the group for the calf."));
            herd.group(ctx, calfGroup);
            for (CalfEntry c : calves) herd.addCalf(ctx, cow, c, r.date(), calfGroup, sire);
        }
        // A new lactation starts; an earlier one still open ends at this calving
        jdbc.sql("update sf_lactations set dry_off_date = :d where tenant_id = :t and animal_id = :a and dry_off_date is null")
                .param("d", r.date()).param("t", ctx.tenantId()).param("a", cow.id()).update();
        int number = last.map(m -> ((Number) m.get("number")).intValue() + 1).orElse(1);
        jdbc.sql("insert into sf_lactations (tenant_id, animal_id, number, calving_date, calving_event_id) values (:t, :a, :n, :d, :e)")
                .param("t", ctx.tenantId()).param("a", cow.id()).param("n", number).param("d", r.date()).param("e", e.id()).update();
        Long milking = r.milkingGroupId() != null ? r.milkingGroupId() : herd.groupFor(ctx, "MILKING").map(Group::id).orElse(null);
        if (milking != null) herd.moveTo(ctx, cow, milking, r.date());
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_CALVED", "ANIMAL", cow.id(), Map.of("date", r.date().toString(), "calves", calves.size()));
        return reports.card(ctx, cow.id());
    }

    @Transactional(readOnly = true)
    public List<Lactation> lactations(TenantContext ctx, long animalId) {
        return jdbc.sql("""
                        select l.number, l.calving_date, l.dry_off_date,
                               (select sum(y.litres) from sf_milk_yields y join sf_milk_sessions s on s.id = y.session_id and s.tenant_id = y.tenant_id
                                where y.tenant_id = l.tenant_id and y.animal_id = l.animal_id and s.milk_date >= l.calving_date
                                  and (l.dry_off_date is null or s.milk_date <= l.dry_off_date)) as litres
                        from sf_lactations l where l.tenant_id = :t and l.animal_id = :a order by l.number""")
                .param("t", ctx.tenantId()).param("a", animalId)
                .query((rs, i) -> new Lactation(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class), rs.getBigDecimal(4))).list();
    }

    /** Deletes a notes-only event (no money or stock attached); records with money are reversed in finance instead. */
    @Transactional
    public void delete(TenantContext ctx, long id) {
        Event e = event(ctx, id);
        if (e.documentId() != null || e.stockDocumentId() != null) {
            throw ApiException.conflict("EVENT_HAS_RECORDS", "This event has a bill or stock use; reverse those records instead.");
        }
        if ("CALVING".equals(e.kind())) throw ApiException.conflict("CALVING_CANNOT_BE_DELETED", "A calving that added calves cannot be deleted.");
        jdbc.sql("delete from sf_animal_events where id = :id and tenant_id = :t").param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_EVENT_DELETED", "ANIMAL_EVENT", id, Map.of("kind", e.kind()));
    }
}
