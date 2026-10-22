package com.smartfarm360.livestock;

import com.smartfarm360.livestock.LivestockDtos.*;
import com.smartfarm360.platform.export.ExportService;
import com.smartfarm360.platform.export.ReportLabels;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.web.Idempotent;
import com.smartfarm360.platform.tenant.FarmModule;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/** Livestock and dairy API (livestock design, approved 2026-10-21). Money-creating requests accept an Idempotency-Key. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/livestock")
@RequiresModule({FarmModule.DAIRY, FarmModule.POULTRY, FarmModule.GOAT_SHEEP})
public class LivestockController {
    private static final String KEY = "Idempotency-Key";
    private static final String ISO = "yyyy-MM-dd";
    private final HerdService herd;
    private final EventService events;
    private final MilkService milk;
    private final LivestockReports reports;
    private final LivestockExports exports;
    private final ExportService export;

    public LivestockController(HerdService herd, EventService events, MilkService milk, LivestockReports reports, LivestockExports exports, ExportService export) {
        this.herd = herd;
        this.events = events;
        this.milk = milk;
        this.reports = reports;
        this.exports = exports;
        this.export = export;
    }

    // ---- Groups ----

    @GetMapping("/groups")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<Group> groups(TenantContext ctx) { return herd.groups(ctx); }

    @PostMapping("/groups")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Group createGroup(TenantContext ctx, @Valid @RequestBody GroupRequest r) { return herd.createGroup(ctx, r); }

    @PatchMapping("/groups/{groupId}")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    public Group updateGroup(TenantContext ctx, @PathVariable long groupId, @Valid @RequestBody GroupPatch r) { return herd.updateGroup(ctx, groupId, r); }

    // ---- Animals ----

    @GetMapping("/animals")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public ResponseEntity<?> animals(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeGone,
                                     @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        List<Animal> list = herd.animals(ctx, includeGone);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(list);
        LocalDate today = LocalDate.now();
        return export.export(ctx, exports.herdRegister(ReportLabels.language(lang), list), format, lang, "herd-register", today, today);
    }

    @PostMapping("/animals")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Animal createAnimal(TenantContext ctx, @Valid @RequestBody AnimalRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return herd.createAnimal(ctx, r, key);
    }

    @GetMapping("/animals/{animalId}")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public AnimalCard card(TenantContext ctx, @PathVariable long animalId) { return reports.card(ctx, animalId); }

    @PatchMapping("/animals/{animalId}")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    public Animal updateAnimal(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody AnimalPatch r) { return herd.updateAnimal(ctx, animalId, r); }

    @PostMapping("/animals/{animalId}/moves")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    public Animal move(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody MoveRequest r) { return herd.move(ctx, animalId, r); }

    @PostMapping("/animals/{animalId}/exit")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    public Animal exit(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody ExitRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return herd.exit(ctx, animalId, r, key);
    }

    @PostMapping("/animals/{animalId}/insurance")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Event insurance(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody InsuranceRequest r,
                           @RequestHeader(value = KEY, required = false) String key) {
        return herd.insurance(ctx, animalId, r, key, events);
    }

    @Idempotent
    @PostMapping("/animals/{animalId}/breeding")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Event breeding(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody BreedingRequest r,
                          @RequestHeader(value = KEY, required = false) String key) {
        return events.breeding(ctx, animalId, r, key);
    }

    @PostMapping("/animals/{animalId}/calving")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public AnimalCard calving(TenantContext ctx, @PathVariable long animalId, @Valid @RequestBody CalvingRequest r) {
        return events.calving(ctx, animalId, r, reports);
    }

    // ---- Health and events ----

    @GetMapping("/events")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public List<Event> events(TenantContext ctx, @RequestParam(required = false) Long animalId,
                              @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                              @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to) {
        return events.events(ctx, animalId, from, to);
    }

    @Idempotent
    @PostMapping("/health")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Event health(TenantContext ctx, @Valid @RequestBody HealthRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return events.health(ctx, r, key);
    }

    @DeleteMapping("/events/{eventId}")
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(TenantContext ctx, @PathVariable long eventId) { events.delete(ctx, eventId); }

    // ---- Milk ----

    @GetMapping("/milk/{date}")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public MilkDay milkDay(TenantContext ctx, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date) { return milk.day(ctx, date); }

    @Idempotent
    @PutMapping("/milk/{date}")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    public MilkDay saveMilkDay(TenantContext ctx, @PathVariable @DateTimeFormat(pattern = ISO) LocalDate date, @Valid @RequestBody MilkDayRequest r) {
        return milk.saveDay(ctx, date, r);
    }

    @PostMapping("/milk-uses")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public List<RecordRef> milkUses(TenantContext ctx, @Valid @RequestBody MilkUseRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return milk.uses(ctx, r, key);
    }

    @PostMapping("/milk-sales")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public RecordRef milkSale(TenantContext ctx, @Valid @RequestBody MilkSaleRequest r, @RequestHeader(value = KEY, required = false) String key) {
        return milk.sale(ctx, r, key);
    }

    // ---- Reports (section 8) ----

    @GetMapping("/reports/milk")
    @RequiresModule(FarmModule.DAIRY)
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public ResponseEntity<?> milkReport(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                        @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                        @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        MilkReport r = reports.milk(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.milk(ReportLabels.language(lang), r), format, lang, "milk", from, to);
    }

    @GetMapping("/reports/herd")
    @RequiresPermission(Permission.FINANCE_READ)
    public ResponseEntity<?> herdReport(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                        @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                        @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        HerdReport r = reports.herd(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.herd(ReportLabels.language(lang), r), format, lang, "herd-profitability", from, to);
    }

    @GetMapping("/reports/breeding")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public ResponseEntity<?> breedingReport(TenantContext ctx, @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(pattern = ISO) LocalDate to,
                                            @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        LocalDate a = from == null ? LocalDate.now() : from, b = to == null ? a.plusDays(60) : to;
        BreedingCalendar r = reports.breeding(ctx, a, b);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.breeding(ReportLabels.language(lang), r), format, lang, "breeding-calendar", a, b);
    }

    @GetMapping("/reports/health")
    @RequiresPermission(Permission.LIVESTOCK_READ)
    public ResponseEntity<?> healthReport(TenantContext ctx, @RequestParam @DateTimeFormat(pattern = ISO) LocalDate from,
                                          @RequestParam @DateTimeFormat(pattern = ISO) LocalDate to,
                                          @RequestParam(required = false) String format, @RequestParam(required = false) String lang) {
        HealthReport r = reports.health(ctx, from, to);
        if (!ExportService.isExport(format)) return ResponseEntity.ok(r);
        return export.export(ctx, exports.health(ReportLabels.language(lang), r), format, lang, "health-record", from, to);
    }
}
