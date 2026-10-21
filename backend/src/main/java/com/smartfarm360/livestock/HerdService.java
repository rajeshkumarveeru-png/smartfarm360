package com.smartfarm360.livestock;

import com.smartfarm360.assets.AssetDtos;
import com.smartfarm360.assets.AssetService;
import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos.Document;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.finance.MasterDataService;
import com.smartfarm360.livestock.LivestockDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The herd (livestock design, sections 3 and 6): groups as cost objects, animals with dated group moves, bought animals in
 * the asset register, and leaving the herd with the right accounting. Breeding state is worked out from events (I10, I11).
 */
@Service
public class HerdService {
    private final JdbcClient jdbc;
    private final CostObjectService costObjects;
    private final MasterDataService master;
    private final AssetService assets;
    private final DocumentService documents;
    private final AuditService audit;

    public HerdService(JdbcClient jdbc, CostObjectService costObjects, MasterDataService master, AssetService assets, DocumentService documents,
                       AuditService audit) {
        this.jdbc = jdbc;
        this.costObjects = costObjects;
        this.master = master;
        this.assets = assets;
        this.documents = documents;
        this.audit = audit;
    }

    // ---- Groups ------------------------------------------------------------------------------------------------------

    private static final String GROUP_SELECT = """
            select g.id, g.cost_object_id, co.name, g.purpose, co.farm_id, g.notes, co.active,
                   (select count(*) from sf_animals a where a.tenant_id = g.tenant_id and a.status = 'ACTIVE'
                      and (select m.group_id from sf_animal_group_moves m where m.tenant_id = a.tenant_id and m.animal_id = a.id
                           and m.from_date <= current_date order by m.from_date desc, m.id desc limit 1) = g.id) as animals
            from sf_animal_groups g join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = g.tenant_id
            where g.tenant_id = :t""";

    private static Group group(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Group(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), (Long) rs.getObject(5), rs.getString(6), rs.getBoolean(7), rs.getInt(8));
    }

    @Transactional(readOnly = true)
    public List<Group> groups(TenantContext ctx) {
        return jdbc.sql(GROUP_SELECT + " order by co.active desc, g.purpose, co.name").param("t", ctx.tenantId()).query((rs, i) -> group(rs)).list();
    }

    public Group group(TenantContext ctx, long id) {
        return jdbc.sql(GROUP_SELECT + " and g.id = :id").param("t", ctx.tenantId()).param("id", id).query((rs, i) -> group(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("ANIMAL_GROUP_NOT_FOUND", "Animal group not found."));
    }

    /** The first active group with a purpose, used for automatic moves at calving and dry-off. */
    Optional<Group> groupFor(TenantContext ctx, String purpose) {
        return groups(ctx).stream().filter(g -> g.active() && g.purpose().equals(purpose)).findFirst();
    }

    @Transactional
    public Group createGroup(TenantContext ctx, GroupRequest r) {
        if (r.farmId() != null) master.requireFarm(ctx.tenantId(), r.farmId());
        long co = costObjects.createAnimalGroup(ctx.tenantId(), r.name().trim(), r.farmId());
        long id = jdbc.sql("insert into sf_animal_groups (tenant_id, cost_object_id, purpose, notes) values (:t, :co, :p, :n) returning id")
                .param("t", ctx.tenantId()).param("co", co).param("p", r.purpose()).param("n", trim(r.notes())).query(Long.class).single();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_GROUP_SAVED", "ANIMAL_GROUP", id, Map.of("name", r.name().trim(), "purpose", r.purpose()));
        return group(ctx, id);
    }

    @Transactional
    public Group updateGroup(TenantContext ctx, long id, GroupPatch p) {
        Group g = group(ctx, id);
        if (p.farmId() != null) master.requireFarm(ctx.tenantId(), p.farmId());
        if (Boolean.FALSE.equals(p.active()) && g.animals() > 0) {
            throw ApiException.conflict("GROUP_HAS_ANIMALS", "Move the animals out of this group before closing it.");
        }
        costObjects.updateAnimalGroup(ctx.tenantId(), g.costObjectId(), p.name() != null ? p.name().trim() : g.name(),
                p.farmId() != null ? p.farmId() : g.farmId(), p.active() != null ? p.active() : g.active());
        jdbc.sql("update sf_animal_groups set purpose = coalesce(:p, purpose), notes = coalesce(:n, notes) where id = :id and tenant_id = :t")
                .param("p", p.purpose()).param("n", p.notes()).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_GROUP_SAVED", "ANIMAL_GROUP", id, Map.of("name", p.name() != null ? p.name().trim() : g.name()));
        return group(ctx, id);
    }

    // ---- Animals -----------------------------------------------------------------------------------------------------

    /** Effective status: an exit whose finance record was reversed returns the animal to the herd (section 6). */
    private static final String ANIMAL_SELECT = """
            select a.id, a.tag, a.name, a.species, a.breed, a.sex, a.birth_date, a.birth_date_estimated, a.origin, a.arrived_on, a.dam_id, d.tag as dam_tag,
                   a.sire, a.farm_id, a.asset_id, a.notes,
                   case when a.exit_document_id is not null and xd.status <> 'POSTED' then 'ACTIVE' else a.status end as status,
                   case when a.exit_document_id is not null and xd.status <> 'POSTED' then null else a.exit_date end as exit_date,
                   case when a.exit_document_id is not null and xd.status <> 'POSTED' then null else a.exit_cause end as exit_cause,
                   case when a.exit_document_id is not null and xd.status <> 'POSTED' then null else a.exit_document_id end as exit_doc,
                   g.id as group_id, co.name as group_name
            from sf_animals a
            left join sf_animals d on d.id = a.dam_id and d.tenant_id = a.tenant_id
            left join sf_finance_documents xd on xd.id = a.exit_document_id and xd.tenant_id = a.tenant_id
            left join lateral (select m.group_id from sf_animal_group_moves m where m.tenant_id = a.tenant_id and m.animal_id = a.id
                               and m.from_date <= greatest(current_date, a.arrived_on) order by m.from_date desc, m.id desc limit 1) gm on true
            left join sf_animal_groups g on g.id = gm.group_id and g.tenant_id = a.tenant_id
            left join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = a.tenant_id
            where a.tenant_id = :t""";

    record AnimalRow(long id, String tag, String name, String species, String breed, String sex, LocalDate birthDate, boolean estimated, String origin,
                     LocalDate arrivedOn, Long damId, String damTag, String sire, Long farmId, Long assetId, String notes, String status,
                     LocalDate exitDate, String exitCause, Long exitDoc, Long groupId, String groupName) {
        boolean active() { return "ACTIVE".equals(status); }
    }

    private static AnimalRow row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AnimalRow(rs.getLong("id"), rs.getString("tag"), rs.getString("name"), rs.getString("species"), rs.getString("breed"),
                rs.getString("sex"), rs.getObject("birth_date", LocalDate.class), rs.getBoolean("birth_date_estimated"), rs.getString("origin"),
                rs.getObject("arrived_on", LocalDate.class), (Long) rs.getObject("dam_id"), rs.getString("dam_tag"), rs.getString("sire"),
                (Long) rs.getObject("farm_id"), (Long) rs.getObject("asset_id"), rs.getString("notes"), rs.getString("status"),
                rs.getObject("exit_date", LocalDate.class), rs.getString("exit_cause"), (Long) rs.getObject("exit_doc"),
                (Long) rs.getObject("group_id"), rs.getString("group_name"));
    }

    AnimalRow animalRow(long tenantId, long id) {
        return jdbc.sql(ANIMAL_SELECT + " and a.id = :id").param("t", tenantId).param("id", id).query((rs, i) -> row(rs)).optional()
                .orElseThrow(() -> ApiException.notFound("ANIMAL_NOT_FOUND", "Animal not found."));
    }

    List<AnimalRow> animalRows(long tenantId, boolean includeGone) {
        return jdbc.sql(ANIMAL_SELECT + " order by lower(a.tag)").param("t", tenantId).query((rs, i) -> row(rs)).list().stream()
                .filter(a -> includeGone || a.active()).toList();
    }

    Animal toAnimal(TenantContext ctx, AnimalRow a, LocalDate today) {
        BigDecimal book = null;
        if (a.assetId() != null && ctx.has(Permission.FINANCE_READ)) book = assets.asset(ctx, a.assetId()).bookValue();
        return new Animal(a.id(), a.tag(), a.name(), a.species(), a.breed(), a.sex(), a.birthDate(), a.estimated(), a.origin(), a.arrivedOn(), a.damId(),
                a.damTag(), a.sire(), a.farmId(), a.groupId(), a.groupName(), a.assetId(), book, a.status(), a.exitDate(), a.exitCause(), a.exitDoc(),
                a.notes(), breeding(ctx.tenantId(), a, today));
    }

    @Transactional(readOnly = true)
    public List<Animal> animals(TenantContext ctx, boolean includeGone) {
        LocalDate today = LocalDate.now();
        return animalRows(ctx.tenantId(), includeGone).stream().map(a -> toAnimal(ctx, a, today)).toList();
    }

    @Transactional(readOnly = true)
    public Animal animal(TenantContext ctx, long id) { return toAnimal(ctx, animalRow(ctx.tenantId(), id), LocalDate.now()); }

    /** The group an animal was in on a date (a cost or a litre belongs to it, I2), or empty before it arrived. */
    Optional<Long> groupOn(long tenantId, long animalId, LocalDate date) {
        return jdbc.sql("""
                        select group_id from sf_animal_group_moves where tenant_id = :t and animal_id = :a and from_date <= :d
                        order by from_date desc, id desc limit 1""")
                .param("t", tenantId).param("a", animalId).param("d", date).query(Long.class).optional();
    }

    @Transactional
    public Animal createAnimal(TenantContext ctx, AnimalRequest r, String key) {
        String tag = r.tag().trim();
        requireFreeTag(ctx.tenantId(), tag, null);
        Group g = group(ctx, r.groupId());
        if (!g.active()) throw ApiException.badRequest("ANIMAL_GROUP_CLOSED", "This group is closed.");
        if (r.farmId() != null) master.requireFarm(ctx.tenantId(), r.farmId());
        if (r.damId() != null) {
            AnimalRow dam = animalRow(ctx.tenantId(), r.damId());
            if (!"FEMALE".equals(dam.sex()) || !dam.species().equals(r.species())) throw ApiException.badRequest("INVALID_DAM", "The dam must be a female of the same species.");
        }
        if (r.arrivedOn().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        if (r.birthDate() != null && r.birthDate().isAfter(r.arrivedOn())) throw ApiException.badRequest("INVALID_BIRTH_DATE", "The birth date is after the animal arrived.");
        Long assetId = null;
        if ("BOUGHT".equals(r.origin())) {
            if (r.purchase() != null && r.assetId() != null) throw ApiException.badRequest("PURCHASE_OR_ASSET", "Record the purchase, or choose the asset already registered, not both.");
            if (r.purchase() != null) {
                Purchase p = r.purchase();
                String label = r.name() != null && !r.name().isBlank() ? tag + " " + r.name().trim() : tag;
                assetId = assets.purchase(ctx, new AssetDtos.PurchaseRequest(r.arrivedOn(), p.partyId(), p.financialAccountId(), p.amount(), p.paidNow(),
                        p.referenceNo(), new AssetDtos.AssetDetails(label, "BREEDING_ANIMAL", r.farmId(), tag, p.usefulLifeMonths(), p.cullValue(),
                        r.arrivedOn(), null, g.costObjectId(), r.notes())), key).id();
            } else if (r.assetId() != null) {
                AssetDtos.Asset a = assets.asset(ctx, r.assetId());
                if (!"BREEDING_ANIMAL".equals(a.kind())) throw ApiException.badRequest("NOT_AN_ANIMAL_ASSET", "Choose an asset registered as a breeding or milch animal.");
                if (jdbc.sql("select exists (select 1 from sf_animals where tenant_id = :t and asset_id = :a)").param("t", ctx.tenantId()).param("a", a.id())
                        .query(Boolean.class).single()) throw ApiException.conflict("ASSET_ALREADY_LINKED", "This asset is already linked to an animal.");
                assetId = a.id();
            }
        } else if (r.purchase() != null || r.assetId() != null) {
            throw ApiException.badRequest("PURCHASE_ONLY_FOR_BOUGHT", "Only bought animals have a purchase or an asset.");
        }
        long id = jdbc.sql("""
                        insert into sf_animals (tenant_id, tag, name, species, breed, sex, birth_date, birth_date_estimated, origin, arrived_on, dam_id, sire,
                          farm_id, asset_id, notes, created_by)
                        values (:t, :tag, :name, :species, :breed, :sex, :birth, :est, :origin, :arrived, :dam, :sire, :farm, :asset, :notes, :u) returning id""")
                .param("t", ctx.tenantId()).param("tag", tag).param("name", trim(r.name())).param("species", r.species()).param("breed", trim(r.breed()))
                .param("sex", r.sex()).param("birth", "BORN".equals(r.origin()) && r.birthDate() == null ? r.arrivedOn() : r.birthDate())
                .param("est", Boolean.TRUE.equals(r.birthDateEstimated())).param("origin", r.origin()).param("arrived", r.arrivedOn())
                .param("dam", r.damId()).param("sire", trim(r.sire())).param("farm", r.farmId()).param("asset", assetId).param("notes", trim(r.notes()))
                .param("u", ctx.userId()).query(Long.class).single();
        insertMove(ctx, id, g.id(), r.arrivedOn());
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_ADDED", "ANIMAL", id, Map.of("tag", tag, "origin", r.origin()));
        return animal(ctx, id);
    }

    /** Adds a calf born on the farm (I11): nil value, dam linked, in the calves group. */
    long addCalf(TenantContext ctx, AnimalRow dam, CalfEntry c, LocalDate date, long groupId, String sire) {
        String tag = c.tag().trim();
        requireFreeTag(ctx.tenantId(), tag, null);
        long id = jdbc.sql("""
                        insert into sf_animals (tenant_id, tag, name, species, sex, birth_date, origin, arrived_on, dam_id, sire, farm_id, created_by)
                        values (:t, :tag, :name, :species, :sex, :d, 'BORN', :d, :dam, :sire, :farm, :u) returning id""")
                .param("t", ctx.tenantId()).param("tag", tag).param("name", trim(c.name())).param("species", dam.species()).param("sex", c.sex())
                .param("d", date).param("dam", dam.id()).param("sire", sire).param("farm", dam.farmId()).param("u", ctx.userId()).query(Long.class).single();
        insertMove(ctx, id, groupId, date);
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_ADDED", "ANIMAL", id, Map.of("tag", tag, "origin", "BORN"));
        return id;
    }

    private void requireFreeTag(long tenantId, String tag, Long except) {
        if (jdbc.sql("select exists (select 1 from sf_animals where tenant_id = :t and lower(tag) = lower(:tag) and (cast(:id as bigint) is null or id <> :id))")
                .param("t", tenantId).param("tag", tag).param("id", except).query(Boolean.class).single()) {
            throw ApiException.conflict("ANIMAL_TAG_TAKEN", "Another animal already has tag " + tag + ".");
        }
    }

    @Transactional
    public Animal updateAnimal(TenantContext ctx, long id, AnimalPatch p) {
        AnimalRow a = animalRow(ctx.tenantId(), id);
        if (p.tag() != null) requireFreeTag(ctx.tenantId(), p.tag().trim(), id);
        if (p.farmId() != null) master.requireFarm(ctx.tenantId(), p.farmId());
        jdbc.sql("""
                        update sf_animals set tag = coalesce(:tag, tag), name = coalesce(:name, name), breed = coalesce(:breed, breed),
                          birth_date = coalesce(:birth, birth_date), birth_date_estimated = coalesce(:est, birth_date_estimated), sire = coalesce(:sire, sire),
                          farm_id = coalesce(:farm, farm_id), notes = coalesce(:notes, notes), updated_at = now()
                        where id = :id and tenant_id = :t""")
                .param("tag", p.tag() == null ? null : p.tag().trim()).param("name", p.name()).param("breed", p.breed()).param("birth", p.birthDate())
                .param("est", p.birthDateEstimated()).param("sire", p.sire()).param("farm", p.farmId()).param("notes", p.notes())
                .param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_UPDATED", "ANIMAL", id, Map.of("tag", p.tag() != null ? p.tag().trim() : a.tag()));
        return animal(ctx, id);
    }

    // ---- Group moves (I2) ------------------------------------------------------------------------------------------------

    @Transactional
    public Animal move(TenantContext ctx, long id, MoveRequest r) {
        AnimalRow a = animalRow(ctx.tenantId(), id);
        if (!a.active()) throw ApiException.conflict("ANIMAL_NOT_ACTIVE", "This animal has left the herd.");
        if (r.date().isBefore(a.arrivedOn())) throw ApiException.badRequest("INVALID_MOVE_DATE", "The animal was not here on that date.");
        if (r.date().isAfter(LocalDate.now())) throw ApiException.badRequest("FUTURE_DATE", "This can only be recorded for today or earlier.");
        moveTo(ctx, a, r.groupId(), r.date());
        return animal(ctx, id);
    }

    /** Moves an animal (also used by calving and dry-off); the asset's depreciation follows to the new group. */
    void moveTo(TenantContext ctx, AnimalRow a, long groupId, LocalDate date) {
        Group g = group(ctx, groupId);
        if (!g.active()) throw ApiException.badRequest("ANIMAL_GROUP_CLOSED", "This group is closed.");
        if (jdbc.sql("select exists (select 1 from sf_animal_group_moves where tenant_id = :t and animal_id = :a and from_date > :d)")
                .param("t", ctx.tenantId()).param("a", a.id()).param("d", date).query(Boolean.class).single()) {
            throw ApiException.conflict("LATER_MOVE_EXISTS", "This animal has a later group move; moves are recorded in date order.");
        }
        if (groupOn(ctx.tenantId(), a.id(), date).filter(x -> x == groupId).isPresent()) return;
        insertMove(ctx, a.id(), groupId, date);
        if (a.assetId() != null) {
            assets.update(ctx, a.assetId(), new AssetDtos.AssetPatch(null, null, null, g.costObjectId(), null, null, null, null, null));
        }
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_MOVED", "ANIMAL", a.id(), Map.of("group", g.name(), "date", date.toString()));
    }

    private void insertMove(TenantContext ctx, long animalId, long groupId, LocalDate date) {
        jdbc.sql("""
                        insert into sf_animal_group_moves (tenant_id, animal_id, group_id, from_date, created_by) values (:t, :a, :g, :d, :u)
                        on conflict (tenant_id, animal_id, from_date) do update set group_id = excluded.group_id""")
                .param("t", ctx.tenantId()).param("a", animalId).param("g", groupId).param("d", date).param("u", ctx.userId()).update();
    }

    @Transactional(readOnly = true)
    public List<GroupMove> moves(TenantContext ctx, long animalId) {
        return jdbc.sql("""
                        select m.group_id, co.name, m.from_date from sf_animal_group_moves m join sf_animal_groups g on g.id = m.group_id and g.tenant_id = m.tenant_id
                        join sf_cost_objects co on co.id = g.cost_object_id and co.tenant_id = g.tenant_id
                        where m.tenant_id = :t and m.animal_id = :a order by m.from_date, m.id""")
                .param("t", ctx.tenantId()).param("a", animalId).query((rs, i) -> new GroupMove(rs.getLong(1), rs.getString(2), rs.getObject(3, LocalDate.class))).list();
    }

    // ---- Leaving the herd (I14) -------------------------------------------------------------------------------------------

    @Transactional
    public Animal exit(TenantContext ctx, long id, ExitRequest r, String key) {
        AnimalRow a = animalRow(ctx.tenantId(), id);
        if (!a.active()) throw ApiException.conflict("ANIMAL_NOT_ACTIVE", "This animal has already left the herd.");
        if (r.date().isBefore(a.arrivedOn())) throw ApiException.badRequest("INVALID_EXIT_DATE", "The animal cannot leave before it arrived.");
        boolean sold = !"DIED".equals(r.kind()) && r.price() != null && r.price().signum() > 0;
        if (!"DIED".equals(r.kind()) && !sold && "SOLD".equals(r.kind())) throw ApiException.badRequest("PRICE_REQUIRED", "Enter the sale price.");
        if (sold && r.partyId() == null && r.financialAccountId() == null) throw ApiException.badRequest("PAYER_REQUIRED", "Choose who bought it or the account the money came into.");
        Long documentId = null;
        long groupCo = groupOn(ctx.tenantId(), a.id(), r.date()).map(g -> group(ctx, g).costObjectId()).orElse(costObjects.overhead(ctx.tenantId()));
        if (a.assetId() != null) {
            if (!ctx.has(Permission.FINANCE_MANAGE)) throw ApiException.forbidden("FINANCE_MANAGE_REQUIRED", "Selling or writing off a registered animal needs permission to manage the accounts.");
            AssetDtos.Asset disposed = assets.dispose(ctx, a.assetId(), new AssetDtos.DisposalRequest(r.date(), sold ? "SOLD" : "SCRAPPED",
                    sold ? r.price() : null, sold ? r.partyId() : null, sold && r.partyId() == null ? r.financialAccountId() : null, trim(r.cause())), key);
            documentId = disposed.disposalDocumentId();
        } else if (sold) {
            if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording a sale needs permission to record money.");
            Document sale = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, r.partyId(), a.farmId(),
                    r.partyId() == null ? r.financialAccountId() : null,
                    List.of(new LineRequest(systemCategory("LIVESTOCK_SALE"), label(a), null, null, null, r.price(), groupCo, a.farmId())),
                    trim(r.cause()), null, true, null), key);
            documentId = sale.summary().id();
        }
        jdbc.sql("update sf_animals set status = :s, exit_date = :d, exit_cause = :c, exit_document_id = :doc, updated_at = now() where id = :id and tenant_id = :t")
                .param("s", r.kind()).param("d", r.date()).param("c", trim(r.cause())).param("doc", documentId).param("id", id).param("t", ctx.tenantId()).update();
        audit.record(ctx.tenantId(), ctx.userId(), "ANIMAL_LEFT", "ANIMAL", id, Map.of("kind", r.kind(), "date", r.date().toString()));
        return animal(ctx, id);
    }

    /** An insurance claim received for an animal: a cash receipt as other income (4100), linked to the animal (section 6). */
    @Transactional
    public Event insurance(TenantContext ctx, long id, InsuranceRequest r, String key, EventService events) {
        AnimalRow a = animalRow(ctx.tenantId(), id);
        if (!ctx.has(Permission.FINANCE_WRITE)) throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording money needs permission to record money.");
        Document receipt = documents.createInvoice(ctx, DocType.SALE, new InvoiceRequest(r.date(), null, null, a.farmId(), r.financialAccountId(),
                List.of(new LineRequest(systemCategory("OTHER_INCOME"), "Insurance: " + label(a), null, null, null, r.amount(), null, a.farmId())),
                trim(r.notes()), null, true, null), key);
        return events.insert(ctx, a.id(), null, "INSURANCE", r.date(), r.notes() == null ? "Insurance claim" : r.notes(), receipt.summary().id(), null);
    }

    static String label(AnimalRow a) { return a.name() == null ? a.tag() : a.tag() + " " + a.name(); }

    long systemCategory(String code) {
        return jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = :c").param("c", code).query(Long.class).single();
    }

    // ---- Breeding state (I10, I11) ---------------------------------------------------------------------------------------

    record EventRow(String kind, LocalDate date, String result, LocalDate checkDueFrom, LocalDate expectedCalving, LocalDate dryOffDue,
                    LocalDate withdrawalUntil) {}

    Breeding breeding(long tenantId, AnimalRow a, LocalDate today) {
        List<EventRow> events = jdbc.sql("""
                        select kind, event_date, result, check_due_from, expected_calving, dry_off_due, withdrawal_until from sf_animal_events
                        where tenant_id = :t and (animal_id = :a or (animal_id is null and group_id in
                          (select m.group_id from sf_animal_group_moves m where m.tenant_id = :t and m.animal_id = :a)))
                        order by event_date, id""")
                .param("t", tenantId).param("a", a.id())
                .query((rs, i) -> new EventRow(rs.getString(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class))).list();
        Map<String, Object> lact = jdbc.sql("""
                        select number, calving_date, dry_off_date from sf_lactations where tenant_id = :t and animal_id = :a order by number desc limit 1""")
                .param("t", tenantId).param("a", a.id()).query().listOfRows().stream().findFirst().orElse(null);
        LocalDate withheld = events.stream().map(EventRow::withdrawalUntil).filter(Objects::nonNull).filter(d -> !d.isBefore(today))
                .max(Comparator.naturalOrder()).orElse(null);
        Integer lactation = lact == null ? null : ((Number) lact.get("number")).intValue();
        LocalDate lastCalving = lact == null ? null : ((Date) lact.get("calving_date")).toLocalDate();
        boolean inMilk = lact != null && lact.get("dry_off_date") == null && a.active();
        if (!"FEMALE".equals(a.sex())) return new Breeding(null, null, null, null, null, null, null, null, false, withheld);
        return derive(events, lactation, lastCalving, inMilk, today, withheld);
    }

    /** Works out the reproductive state from events in date order (I10): a negative check or a new heat clears the dates. */
    static Breeding derive(List<EventRow> events, Integer lactation, LocalDate lastCalving, boolean inMilk, LocalDate today, LocalDate withheld) {
        String repro = "OPEN";
        LocalDate insemination = null, check = null, calving = null, dryOff = null;
        for (EventRow e : events) {
            switch (e.kind()) {
                case "INSEMINATION" -> { repro = "INSEMINATED"; insemination = e.date(); check = e.checkDueFrom(); calving = e.expectedCalving(); dryOff = e.dryOffDue(); }
                case "PREGNANCY_CHECK" -> {
                    if ("POSITIVE".equals(e.result()) && insemination != null) repro = "PREGNANT";
                    else if ("NEGATIVE".equals(e.result())) { repro = "OPEN"; check = null; calving = null; dryOff = null; }
                }
                case "HEAT" -> { if (!"PREGNANT".equals(repro)) { repro = "OPEN"; check = null; calving = null; dryOff = null; } }
                case "CALVING", "ABORTION" -> { repro = "OPEN"; insemination = null; check = null; calving = null; dryOff = null; }
                default -> { }
            }
        }
        if ("PREGNANT".equals(repro)) check = null;
        Long dim = inMilk && lastCalving != null ? ChronoUnit.DAYS.between(lastCalving, today) : null;
        return new Breeding(repro, insemination, check, calving, dryOff, lactation, lastCalving, dim, inMilk, withheld);
    }

    static String trim(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
