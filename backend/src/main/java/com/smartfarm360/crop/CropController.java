package com.smartfarm360.crop;

import com.smartfarm360.crop.CropDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Crop API (crop management design, approved 2026-10-20). Recording activities and harvests, and the cycle reports,
 * are in the operations API because they move stock and money.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiresModule(FarmModule.CROP)
public class CropController {
    private final CropService service;

    public CropController(CropService service) { this.service = service; }

    @GetMapping("/crops")
    @RequiresPermission(Permission.CROP_READ)
    public List<Crop> crops(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeInactive) { return service.crops(ctx, includeInactive); }

    @PostMapping("/crops")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Crop crop(TenantContext ctx, @Valid @RequestBody CropRequest b) { return service.createCrop(ctx, b); }

    @PatchMapping("/crops/{cropId}")
    @RequiresPermission(Permission.CROP_WRITE)
    public Crop updateCrop(TenantContext ctx, @PathVariable long cropId, @Valid @RequestBody CropPatch b) { return service.updateCrop(ctx, cropId, b); }

    @GetMapping("/crops/{cropId}/varieties")
    @RequiresPermission(Permission.CROP_READ)
    public List<Variety> varieties(TenantContext ctx, @PathVariable long cropId) { return service.varieties(ctx, cropId); }

    @PostMapping("/crops/{cropId}/varieties")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Variety variety(TenantContext ctx, @PathVariable long cropId, @Valid @RequestBody VarietyRequest b) { return service.createVariety(ctx, cropId, b); }

    @GetMapping("/seasons")
    @RequiresPermission(Permission.CROP_READ)
    public List<Season> seasons(TenantContext ctx) { return service.seasons(ctx); }

    @PostMapping("/seasons")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Season season(TenantContext ctx, @Valid @RequestBody SeasonRequest b) { return service.createSeason(ctx, b); }

    @GetMapping("/cultivation-cycles")
    @RequiresPermission(Permission.CROP_READ)
    public PageResponse<Cycle> cycles(TenantContext ctx, @RequestParam(required = false) Long farmId, @RequestParam(required = false) Long cropId,
                                      @RequestParam(required = false) Long seasonId, @RequestParam(required = false) String status,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.cycles(ctx, farmId, cropId, seasonId, status, page, size);
    }

    @PostMapping("/cultivation-cycles")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Cycle cycle(TenantContext ctx, @Valid @RequestBody CycleRequest b) { return service.createCycle(ctx, b); }

    @GetMapping("/cultivation-cycles/{cycleId}")
    @RequiresPermission(Permission.CROP_READ)
    public Cycle cycle(TenantContext ctx, @PathVariable long cycleId) { return service.cycle(ctx, cycleId); }

    @PatchMapping("/cultivation-cycles/{cycleId}")
    @RequiresPermission(Permission.CROP_WRITE)
    public Cycle updateCycle(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody CyclePatch b) { return service.updateCycle(ctx, cycleId, b); }

    @PostMapping("/cultivation-cycles/{cycleId}/status")
    @RequiresPermission(Permission.CROP_WRITE)
    public Cycle status(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody StatusRequest b) { return service.changeStatus(ctx, cycleId, b); }

    @GetMapping("/activities")
    @RequiresPermission(Permission.CROP_READ)
    public PageResponse<Activity> activities(TenantContext ctx, @RequestParam(required = false) Long cycleId,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.activities(ctx, cycleId, page, size);
    }

    @PatchMapping("/activities/{activityId}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    public Activity updateActivity(TenantContext ctx, @PathVariable long activityId, @Valid @RequestBody ActivityPatch b) {
        return service.updateActivity(ctx, activityId, b);
    }

    @DeleteMapping("/activities/{activityId}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteActivity(TenantContext ctx, @PathVariable long activityId) { service.deleteActivity(ctx, activityId); }

    @GetMapping("/harvests")
    @RequiresPermission(Permission.CROP_READ)
    public PageResponse<Harvest> harvests(TenantContext ctx, @RequestParam(required = false) Long cycleId,
                                          @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.harvests(ctx, cycleId, page, size);
    }

    @GetMapping("/cultivation-cycles/{cycleId}/observations")
    @RequiresPermission(Permission.CROP_READ)
    public List<Observation> observations(TenantContext ctx, @PathVariable long cycleId) { return service.observations(ctx, cycleId); }

    @PostMapping("/cultivation-cycles/{cycleId}/observations")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Observation observation(TenantContext ctx, @PathVariable long cycleId, @Valid @RequestBody ObservationRequest b) {
        return service.createObservation(ctx, cycleId, b);
    }

    @PutMapping("/observations/{observationId}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    public Observation updateObservation(TenantContext ctx, @PathVariable long observationId, @Valid @RequestBody ObservationRequest b) {
        return service.updateObservation(ctx, observationId, b);
    }

    @DeleteMapping("/observations/{observationId}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteObservation(TenantContext ctx, @PathVariable long observationId) { service.deleteObservation(ctx, observationId); }

    @GetMapping("/farms/{farmId}/weather")
    @RequiresPermission(Permission.CROP_READ)
    public Weather weather(TenantContext ctx, @PathVariable long farmId, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.weather(ctx, farmId, from, to);
    }

    @PutMapping("/farms/{farmId}/weather/{date}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    public WeatherNote saveWeather(TenantContext ctx, @PathVariable long farmId, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @Valid @RequestBody WeatherRequest b) {
        return service.saveWeather(ctx, farmId, date, b);
    }

    @DeleteMapping("/farms/{farmId}/weather/{date}")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWeather(TenantContext ctx, @PathVariable long farmId, @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        service.deleteWeather(ctx, farmId, date);
    }
}
