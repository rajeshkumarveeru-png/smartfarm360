package com.smartfarm360.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.crop.CropDtos.Activity;
import com.smartfarm360.crop.CropDtos.Crop;
import com.smartfarm360.crop.CropDtos.Harvest;
import com.smartfarm360.crop.CropService;
import com.smartfarm360.crop.CropService.CycleRow;
import com.smartfarm360.finance.DocType;
import com.smartfarm360.finance.DocumentService;
import com.smartfarm360.finance.FinanceDtos;
import com.smartfarm360.finance.FinanceDtos.InvoiceRequest;
import com.smartfarm360.finance.FinanceDtos.LineRequest;
import com.smartfarm360.inventory.ItemService;
import com.smartfarm360.inventory.StockDtos;
import com.smartfarm360.inventory.StockDtos.ItemQuantity;
import com.smartfarm360.inventory.StockRecordService;
import com.smartfarm360.operations.OperationsDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Field work recorded once (crop design, principle 1): an activity's stock inputs become the stock-use record and its
 * paid work a bill, both assigned to the cycle; a harvest becomes the farm-produce record and a lot. Everything in one
 * transaction, and retries with the same Idempotency-Key record nothing twice.
 */
@Service
public class FieldOperationsService {
    private final JdbcClient jdbc;
    private final CropService crops;
    private final CostObjectService costObjects;
    private final StockRecordService stockRecords;
    private final ItemService items;
    private final DocumentService documents;
    private final ObjectMapper json;

    public FieldOperationsService(JdbcClient jdbc, CropService crops, CostObjectService costObjects, StockRecordService stockRecords,
                                  ItemService items, DocumentService documents, ObjectMapper json) {
        this.jdbc = jdbc;
        this.crops = crops;
        this.costObjects = costObjects;
        this.stockRecords = stockRecords;
        this.items = items;
        this.documents = documents;
        this.json = json;
    }

    // ---- Activities (section 4) -----------------------------------------------------------------------------------

    @Transactional
    public ActivityResult recordActivity(TenantContext ctx, ActivityRequest r, String key) {
        String hash = hash("ACTIVITY", r);
        Optional<Map.Entry<Long, String>> earlier = key == null ? Optional.empty() : crops.activityByKey(ctx.tenantId(), checkKey(key));
        if (earlier.isPresent()) return new ActivityResult(crops.activity(ctx, replayed(earlier.get(), hash)), List.of());
        List<Input> inputs = r.inputs() == null ? List.of() : r.inputs();
        if (!inputs.isEmpty() && !ctx.has(Permission.INVENTORY_WRITE)) {
            throw ApiException.forbidden("STOCK_PERMISSION_REQUIRED", "Using stock needs permission to record stock.");
        }
        if (r.paidWork() != null && !ctx.has(Permission.FINANCE_WRITE)) {
            throw ApiException.forbidden("FINANCE_PERMISSION_REQUIRED", "Recording paid work needs permission to record money.");
        }
        CycleRow cycle = crops.requireOpenCycle(ctx.tenantId(), r.cycleId());
        long costObject = costObjects.forCycle(ctx.tenantId(), cycle.id()).orElseThrow();
        long id = crops.insertActivity(ctx, cycle.id(), r.activityType(), r.activityDate(), r.notes(), r.workers(), r.hours(), key, hash);
        String label = cycle.name() + " · " + r.activityType().toLowerCase(Locale.ROOT).replace('_', ' ');

        Long stockDoc = null;
        List<String> warnings = List.of();
        if (!inputs.isEmpty()) {
            StockDtos.StockRecordResult issued = stockRecords.issue(ctx, new StockDtos.IssueRequest(r.activityDate(), r.locationId(), costObject,
                    cycle.farmId(), inputs.stream().map(i -> new ItemQuantity(i.itemId(), i.quantity(), i.unit(), i.lotNo(), null)).toList(), label),
                    key == null ? null : key + ":inputs");
            stockDoc = issued.document().summary().id();
            warnings = issued.warnings();
        }
        Long billDoc = null;
        if (r.paidWork() != null) {
            PaidWork p = r.paidWork();
            if (p.partyId() == null && p.financialAccountId() == null) {
                throw ApiException.badRequest("PARTY_OR_ACCOUNT_REQUIRED", "Choose who is owed, or the cash/bank account it was paid from.");
            }
            String description = p.description() == null || p.description().isBlank() ? label : p.description().trim();
            billDoc = documents.createInvoice(ctx, DocType.BILL, new InvoiceRequest(r.activityDate(), null, p.partyId(), cycle.farmId(),
                    p.partyId() == null ? p.financialAccountId() : null,
                    List.of(new LineRequest(p.categoryId(), description, null, null, null, p.amount(), costObject, cycle.farmId())),
                    label, null, true, null), key == null ? null : key + ":paid").summary().id();
        }
        crops.linkActivity(ctx.tenantId(), id, stockDoc, billDoc);
        return new ActivityResult(crops.activity(ctx, id), warnings);
    }

    /** Reverses the stock use and bill an activity created (refused in locked periods or when its stock moved on). */
    @Transactional
    public Activity reverseActivity(TenantContext ctx, long activityId, ReverseRequest r) {
        Activity a = crops.activity(ctx, activityId);
        if (!"ACTIVE".equals(a.status())) throw ApiException.conflict("ALREADY_REVERSED", "This record is already reversed.");
        if (a.stockDocumentId() == null && a.billDocumentId() == null) {
            throw ApiException.conflict("ACTIVITY_HAS_NO_RECORDS", "This activity moved no stock or money; edit or delete it instead.");
        }
        if (!ctx.has(Permission.FINANCE_REVERSE)) throw ApiException.forbidden("REVERSE_PERMISSION_REQUIRED", "Reversing needs permission to reverse records.");
        FinanceDtos.ReverseRequest reverse = new FinanceDtos.ReverseRequest(r == null ? null : r.reversalDate());
        if (a.billDocumentId() != null) reverseIfPosted(ctx, a.billDocumentId(), reverse);
        if (a.stockDocumentId() != null) reverseIfPosted(ctx, a.stockDocumentId(), reverse);
        crops.markActivityReversed(ctx, activityId);
        return crops.activity(ctx, activityId);
    }

    private void reverseIfPosted(TenantContext ctx, long documentId, FinanceDtos.ReverseRequest r) {
        if ("POSTED".equals(documents.document(ctx, documentId).summary().status())) documents.reverse(ctx, documentId, r);
    }

    // ---- Harvests (section 5) -------------------------------------------------------------------------------------

    @Transactional
    public HarvestResult recordHarvest(TenantContext ctx, HarvestRequest r, String key) {
        String hash = hash("HARVEST", r);
        Optional<Map.Entry<Long, String>> earlier = key == null ? Optional.empty() : crops.harvestByKey(ctx.tenantId(), checkKey(key));
        if (earlier.isPresent()) return new HarvestResult(crops.harvest(ctx, replayed(earlier.get(), hash)), List.of());
        CycleRow cycle = crops.requireOpenCycle(ctx.tenantId(), r.cycleId());
        long costObject = costObjects.forCycle(ctx.tenantId(), cycle.id()).orElseThrow();
        StockDtos.Item item = produceItem(ctx, cycle.cropId());
        int n = crops.recordedHarvest(ctx.tenantId(), cycle.id());
        String lotNo = item.trackLots() ? lotName(ctx.tenantId(), item.id(), cycle, n) : null;
        StockDtos.StockRecordResult produced = stockRecords.produce(ctx, new StockDtos.ProduceRequest(r.harvestDate(), r.locationId(), costObject,
                        cycle.farmId(), List.of(new ItemQuantity(item.id(), r.quantity(), r.unit(), lotNo, null)), "Harvest · " + cycle.name()),
                key == null ? null : key + ":produce");
        FinanceDtos.Document doc = produced.document();
        BigDecimal base = doc.movements().get(0).quantity();
        Long lotId = lotNo == null ? null : jdbc.sql("select id from sf_stock_lots where tenant_id = :t and item_id = :i and lot_no = :n")
                .param("t", ctx.tenantId()).param("i", item.id()).param("n", lotNo).query(Long.class).single();
        String unit = r.unit() == null || r.unit().isBlank() ? (item.customUnitName() != null ? item.customUnitName() : item.baseUnit()) : r.unit().trim();
        long id = crops.insertHarvest(ctx, cycle.id(), r.harvestDate(), r.quantity(), unit, item.id(), base, lotId, doc.summary().id(), r.grade(),
                r.moisturePct(), r.notes(), key, hash);
        return new HarvestResult(crops.harvest(ctx, id), produced.warnings());
    }

    @Transactional
    public Harvest reverseHarvest(TenantContext ctx, long harvestId, ReverseRequest r) {
        Harvest h = crops.harvest(ctx, harvestId);
        if (h.legacy()) throw ApiException.conflict("LEGACY_RECORD", "Records from before Phase 5 are kept as history.");
        if (!"ACTIVE".equals(h.status())) throw ApiException.conflict("ALREADY_REVERSED", "This record is already reversed.");
        if (!ctx.has(Permission.FINANCE_REVERSE)) throw ApiException.forbidden("REVERSE_PERMISSION_REQUIRED", "Reversing needs permission to reverse records.");
        crops.lockCycle(ctx.tenantId(), h.cycleId());
        reverseIfPosted(ctx, h.documentId(), new FinanceDtos.ReverseRequest(r == null ? null : r.reversalDate()));
        crops.markHarvestReversed(ctx, harvestId, h.cycleId());
        return crops.harvest(ctx, harvestId);
    }

    /**
     * The stock item a crop's harvests go into. Created on first use (farm produce, counted in kg, tracking lots so each
     * harvest is a lot) unless a farm-produce item with the crop's name exists.
     */
    @Transactional
    public StockDtos.Item produceItem(TenantContext ctx, long cropId) {
        Crop crop = crops.crop(ctx, cropId);
        if (crop.produceItemId() != null) return items.item(ctx, crop.produceItemId());
        Optional<StockDtos.Item> same = items.items(ctx, true).stream()
                .filter(i -> i.name().equalsIgnoreCase(crop.name()) && "PRODUCE".equals(i.kind())).findFirst();
        StockDtos.Item item = same.orElseGet(() -> {
            boolean nameTaken = items.items(ctx, true).stream().anyMatch(i -> i.name().equalsIgnoreCase(crop.name()));
            return items.createItem(ctx, new StockDtos.ItemRequest(nameTaken ? crop.name() + " (harvest)" : crop.name(), "PRODUCE", "kg", null,
                    systemCategory("CROP_SALE"), null, true, null, List.of()));
        });
        crops.setProduceItem(ctx, cropId, item.id());
        return item;
    }

    @Transactional(readOnly = true)
    public StockDtos.Item currentProduceItem(TenantContext ctx, long cropId) {
        Crop crop = crops.crop(ctx, cropId);
        return crop.produceItemId() == null ? null : items.item(ctx, crop.produceItemId());
    }

    @Transactional
    public StockDtos.Item setProduceItem(TenantContext ctx, long cropId, long itemId) {
        crops.crop(ctx, cropId);
        StockDtos.Item item = items.item(ctx, itemId);
        if (!"PRODUCE".equals(item.kind())) throw ApiException.badRequest("NOT_FARM_PRODUCE", "Choose a farm-produce item for the harvest.");
        crops.setProduceItem(ctx, cropId, itemId);
        return item;
    }

    /** "Samba 2026", third harvest → SAMBA-2026-H3; unique for the item. */
    private String lotName(long tenantId, long itemId, CycleRow cycle, int n) {
        String stem = cycle.name().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-").replaceAll("^-|-$", "");
        if (stem.isEmpty()) stem = "CYCLE-" + cycle.id();
        if (stem.length() > 40) stem = stem.substring(0, 40);
        String lot = stem + "-H" + n;
        boolean taken = jdbc.sql("select exists (select 1 from sf_stock_lots where tenant_id = :t and item_id = :i and lot_no = :n)")
                .param("t", tenantId).param("i", itemId).param("n", lot).query(Boolean.class).single();
        return taken ? stem + "-" + cycle.id() + "-H" + n : lot;
    }

    private Long systemCategory(String code) {
        return jdbc.sql("select id from sf_finance_categories where tenant_id is null and code = :c").param("c", code).query(Long.class).single();
    }

    private static String checkKey(String key) {
        if (key.length() > 80) throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key is too long.");
        return key;
    }

    private static long replayed(Map.Entry<Long, String> earlier, String hash) {
        if (!earlier.getValue().equals(hash)) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This request key was already used for a different record.");
        }
        return earlier.getKey();
    }

    private String hash(String type, Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((type + ":" + json.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot hash request", e);
        }
    }

}
