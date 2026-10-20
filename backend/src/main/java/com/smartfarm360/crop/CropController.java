package com.smartfarm360.crop;

import com.smartfarm360.crop.CropDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresModule;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
@RequiresModule(FarmModule.CROP)
public class CropController {
    private final CropService service;

    public CropController(CropService service) { this.service = service; }

    @GetMapping("/crops")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> crops(TenantContext ctx) { return service.crops(ctx); }

    @PostMapping("/crops")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> crop(TenantContext ctx, @Valid @RequestBody CropRequest b) { return service.createCrop(ctx, b); }

    @GetMapping("/crops/{cropId}/varieties")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> varieties(TenantContext ctx, @PathVariable long cropId) { return service.varieties(ctx, cropId); }

    @PostMapping("/crops/{cropId}/varieties")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> variety(TenantContext ctx, @PathVariable long cropId, @Valid @RequestBody VarietyRequest b) {
        return service.createVariety(ctx, cropId, b);
    }

    @GetMapping("/seasons")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> seasons(TenantContext ctx) { return service.seasons(ctx); }

    @PostMapping("/seasons")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> season(TenantContext ctx, @Valid @RequestBody SeasonRequest b) { return service.createSeason(ctx, b); }

    @GetMapping("/cultivation-cycles")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> cycles(TenantContext ctx, @RequestParam(required = false) Long farmId) { return service.cycles(ctx, farmId); }

    @PostMapping("/cultivation-cycles")
    @RequiresPermission(Permission.CROP_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> cycle(TenantContext ctx, @Valid @RequestBody CycleRequest b) { return service.createCycle(ctx, b); }

    @GetMapping("/activities")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> activities(TenantContext ctx, @RequestParam(required = false) Long cycleId) { return service.activities(ctx, cycleId); }

    @PostMapping("/activities")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> activity(TenantContext ctx, @Valid @RequestBody ActivityRequest b) { return service.createActivity(ctx, b); }

    @GetMapping("/harvests")
    @RequiresPermission(Permission.CROP_READ)
    public List<Map<String, Object>> harvests(TenantContext ctx, @RequestParam(required = false) Long cycleId) { return service.harvests(ctx, cycleId); }

    @PostMapping("/harvests")
    @RequiresPermission(Permission.CROP_ACTIVITY_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> harvest(TenantContext ctx, @Valid @RequestBody HarvestRequest b) { return service.createHarvest(ctx, b); }
}
