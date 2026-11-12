package com.smartfarm360.farm;

import com.smartfarm360.farm.FarmDtos.*;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.SfTenantRepository;
import com.smartfarm360.platform.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Farms and land parcels. Every lookup is scoped by the validated tenant, so other tenants' ids return 404. */
@Service
public class FarmService {
    private final SfFarmRepository farms;
    private final SfLandParcelRepository parcels;
    private final SfTenantRepository tenants;
    private final AuditService audit;
    private final com.smartfarm360.platform.plan.PlanLimits limits;

    public FarmService(SfFarmRepository farms, SfLandParcelRepository parcels, SfTenantRepository tenants, AuditService audit,
                       com.smartfarm360.platform.plan.PlanLimits limits) {
        this.limits = limits;
        this.farms = farms;
        this.parcels = parcels;
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Farm> farms(TenantContext ctx, boolean includeInactive) {
        List<SfFarm> list = includeInactive ? farms.findByTenantIdOrderByNameAsc(ctx.tenantId())
                : farms.findByTenantIdAndActiveTrueOrderByNameAsc(ctx.tenantId());
        return list.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Farm farm(TenantContext ctx, long farmId) { return toResponse(farmEntity(ctx, farmId)); }

    @Transactional
    public Farm createFarm(TenantContext ctx, FarmRequest request) {
        limits.requireFarmSlot(ctx.tenantId());
        SfFarm farm = new SfFarm();
        farm.setTenant(tenants.getReferenceById(ctx.tenantId()));
        farm.setName(request.name().trim());
        if (request.farmType() != null) farm.setFarmType(request.farmType());
        farm.setVillage(trimToNull(request.village()));
        farm.setDistrict(trimToNull(request.district()));
        if (request.state() != null && !request.state().isBlank()) farm.setState(request.state().trim());
        farm = farms.saveAndFlush(farm);
        if (request.area() != null) {
            SfLandParcel initialParcel = new SfLandParcel();
            initialParcel.setTenant(farm.getTenant());
            initialParcel.setFarm(farm);
            initialParcel.setName("Main land");
            initialParcel.setArea(request.area());
            if (request.areaUnit() != null) initialParcel.setAreaUnit(request.areaUnit());
            parcels.saveAndFlush(initialParcel);
        }
        audit.record(ctx.tenantId(), ctx.userId(), "FARM_CREATED", "FARM", farm.getId(), Map.of("name", farm.getName()));
        return toResponse(farm);
    }

    @Transactional
    public Farm updateFarm(TenantContext ctx, long farmId, FarmPatch patch) {
        SfFarm farm = farmEntity(ctx, farmId);
        if (patch.name() != null) farm.setName(requireText(patch.name()));
        if (patch.farmType() != null) farm.setFarmType(patch.farmType());
        if (patch.village() != null) farm.setVillage(trimToNull(patch.village()));
        if (patch.district() != null) farm.setDistrict(trimToNull(patch.district()));
        if (patch.state() != null) farm.setState(trimToNull(patch.state()));
        farms.saveAndFlush(farm);
        audit.record(ctx.tenantId(), ctx.userId(), "FARM_UPDATED", "FARM", farmId, Map.of("name", farm.getName()));
        return toResponse(farm);
    }

    @Transactional(readOnly = true)
    public List<LandParcel> parcels(TenantContext ctx, long farmId, boolean includeInactive) {
        farmEntity(ctx, farmId);
        List<SfLandParcel> list = includeInactive ? parcels.findByTenantIdAndFarmIdOrderByNameAsc(ctx.tenantId(), farmId)
                : parcels.findByTenantIdAndFarmIdAndActiveTrueOrderByNameAsc(ctx.tenantId(), farmId);
        return list.stream().map(FarmService::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public LandParcel parcel(TenantContext ctx, long parcelId) { return toResponse(parcelEntity(ctx, parcelId)); }

    @Transactional
    public LandParcel createParcel(TenantContext ctx, long farmId, LandParcelRequest request) {
        SfFarm farm = farmEntity(ctx, farmId);
        if (!farm.isActive()) throw ApiException.conflict("FARM_INACTIVE", "Land cannot be added to an inactive farm.");
        SfLandParcel parcel = new SfLandParcel();
        parcel.setTenant(farm.getTenant());
        parcel.setFarm(farm);
        parcel.setName(request.name().trim());
        parcel.setArea(request.area());
        if (request.areaUnit() != null) parcel.setAreaUnit(request.areaUnit());
        parcel.setSoilType(trimToNull(request.soilType()));
        parcel.setIrrigationType(trimToNull(request.irrigationType()));
        parcel = parcels.saveAndFlush(parcel);
        audit.record(ctx.tenantId(), ctx.userId(), "LAND_PARCEL_CREATED", "LAND_PARCEL", parcel.getId(),
                Map.of("farmId", farmId, "name", parcel.getName()));
        return toResponse(parcel);
    }

    @Transactional
    public LandParcel updateParcel(TenantContext ctx, long parcelId, LandParcelPatch patch) {
        SfLandParcel parcel = parcelEntity(ctx, parcelId);
        if (patch.name() != null) parcel.setName(requireText(patch.name()));
        if (patch.area() != null) parcel.setArea(patch.area());
        if (patch.areaUnit() != null) parcel.setAreaUnit(patch.areaUnit());
        if (patch.soilType() != null) parcel.setSoilType(trimToNull(patch.soilType()));
        if (patch.irrigationType() != null) parcel.setIrrigationType(trimToNull(patch.irrigationType()));
        parcels.saveAndFlush(parcel);
        audit.record(ctx.tenantId(), ctx.userId(), "LAND_PARCEL_UPDATED", "LAND_PARCEL", parcelId, Map.of("name", parcel.getName()));
        return toResponse(parcel);
    }

    private SfFarm farmEntity(TenantContext ctx, long farmId) {
        return farms.findByIdAndTenantId(farmId, ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("FARM_NOT_FOUND", "Farm not found."));
    }

    private SfLandParcel parcelEntity(TenantContext ctx, long parcelId) {
        return parcels.findByIdAndTenantId(parcelId, ctx.tenantId())
                .orElseThrow(() -> ApiException.notFound("LAND_PARCEL_NOT_FOUND", "Land parcel not found."));
    }

    private static String requireText(String s) {
        if (s.isBlank()) throw ApiException.badRequest("VALIDATION_FAILED", "Name is required.");
        return s.trim();
    }

    private static String trimToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private Farm toResponse(SfFarm f) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        parcels.findByTenantIdAndFarmIdAndActiveTrueOrderByNameAsc(f.getTenant().getId(), f.getId())
                .forEach(p -> totals.merge(p.getAreaUnit(), p.getArea(), BigDecimal::add));
        String areaSummary = totals.entrySet().stream()
                .map(e -> e.getValue().stripTrailingZeros().toPlainString() + " " + e.getKey())
                .collect(java.util.stream.Collectors.joining(" · "));
        return new Farm(f.getId(), f.getTenant().getId(), f.getName(), f.getFarmType(), f.getVillage(), f.getDistrict(),
                f.getState(), f.getCountry(), f.isActive(), f.getCreatedAt(), areaSummary.isBlank() ? null : areaSummary);
    }

    private static LandParcel toResponse(SfLandParcel p) {
        return new LandParcel(p.getId(), p.getTenant().getId(), p.getFarm().getId(), p.getName(), p.getArea(), p.getAreaUnit(),
                p.getSoilType(), p.getIrrigationType(), p.isActive(), p.getCreatedAt());
    }
}
