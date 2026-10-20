package com.smartfarm360.farm;

import com.smartfarm360.farm.FarmDtos.*;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.RequiresPermission;
import com.smartfarm360.platform.security.TenantContext;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class FarmController {
    private final FarmService service;

    public FarmController(FarmService service) { this.service = service; }

    @GetMapping("/farms")
    @RequiresPermission(Permission.FARM_READ)
    public List<Farm> farms(TenantContext ctx, @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.farms(ctx, includeInactive);
    }

    @PostMapping("/farms")
    @RequiresPermission(Permission.FARM_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public Farm createFarm(TenantContext ctx, @Valid @RequestBody FarmRequest request) { return service.createFarm(ctx, request); }

    @GetMapping("/farms/{farmId}")
    @RequiresPermission(Permission.FARM_READ)
    public Farm farm(TenantContext ctx, @PathVariable long farmId) { return service.farm(ctx, farmId); }

    @PatchMapping("/farms/{farmId}")
    @RequiresPermission(Permission.FARM_WRITE)
    public Farm updateFarm(TenantContext ctx, @PathVariable long farmId, @Valid @RequestBody FarmPatch patch) {
        return service.updateFarm(ctx, farmId, patch);
    }

    @DeleteMapping("/farms/{farmId}")
    @RequiresPermission(Permission.FARM_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateFarm(TenantContext ctx, @PathVariable long farmId) { service.deactivateFarm(ctx, farmId); }

    @GetMapping("/farms/{farmId}/land-parcels")
    @RequiresPermission(Permission.FARM_READ)
    public List<LandParcel> parcels(TenantContext ctx, @PathVariable long farmId,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.parcels(ctx, farmId, includeInactive);
    }

    @PostMapping("/farms/{farmId}/land-parcels")
    @RequiresPermission(Permission.FARM_WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    public LandParcel createParcel(TenantContext ctx, @PathVariable long farmId, @Valid @RequestBody LandParcelRequest request) {
        return service.createParcel(ctx, farmId, request);
    }

    @GetMapping("/land-parcels/{parcelId}")
    @RequiresPermission(Permission.FARM_READ)
    public LandParcel parcel(TenantContext ctx, @PathVariable long parcelId) { return service.parcel(ctx, parcelId); }

    @PatchMapping("/land-parcels/{parcelId}")
    @RequiresPermission(Permission.FARM_WRITE)
    public LandParcel updateParcel(TenantContext ctx, @PathVariable long parcelId, @Valid @RequestBody LandParcelPatch patch) {
        return service.updateParcel(ctx, parcelId, patch);
    }

    @DeleteMapping("/land-parcels/{parcelId}")
    @RequiresPermission(Permission.FARM_WRITE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateParcel(TenantContext ctx, @PathVariable long parcelId) { service.deactivateParcel(ctx, parcelId); }
}
