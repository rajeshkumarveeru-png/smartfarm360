package com.smartfarm360.controller;

import com.smartfarm360.dto.SfRequests;
import com.smartfarm360.dto.SfResponses;
import com.smartfarm360.service.SmartFarmService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/smartfarm")
public class SmartFarmController {
    private final SmartFarmService service;
    public SmartFarmController(SmartFarmService service) { this.service = service; }

    @PostMapping("/tenants") @ResponseStatus(HttpStatus.CREATED)
    public SfResponses.Tenant createTenant(Authentication auth, @Valid @RequestBody SfRequests.CreateTenant request) { return service.createTenant(auth.getName(), request); }
    @GetMapping("/tenants")
    public List<SfResponses.Tenant> tenants(Authentication auth) { return service.tenants(auth.getName()); }
    @PostMapping("/tenants/{tenantId}/farms") @ResponseStatus(HttpStatus.CREATED)
    public SfResponses.Farm createFarm(Authentication auth, @PathVariable Long tenantId, @Valid @RequestBody SfRequests.CreateFarm request) { return service.createFarm(auth.getName(), tenantId, request); }
    @GetMapping("/tenants/{tenantId}/farms")
    public List<SfResponses.Farm> farms(Authentication auth, @PathVariable Long tenantId) { return service.farms(auth.getName(), tenantId); }
    @PostMapping("/tenants/{tenantId}/farms/{farmId}/land-parcels") @ResponseStatus(HttpStatus.CREATED)
    public SfResponses.LandParcel createParcel(Authentication auth, @PathVariable Long tenantId, @PathVariable Long farmId, @Valid @RequestBody SfRequests.CreateLandParcel request) { return service.createParcel(auth.getName(), tenantId, farmId, request); }
    @GetMapping("/tenants/{tenantId}/farms/{farmId}/land-parcels")
    public List<SfResponses.LandParcel> parcels(Authentication auth, @PathVariable Long tenantId, @PathVariable Long farmId) { return service.parcels(auth.getName(), tenantId, farmId); }
}
