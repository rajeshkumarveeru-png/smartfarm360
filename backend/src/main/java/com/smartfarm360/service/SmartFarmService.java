package com.smartfarm360.service;

import com.smartfarm360.entity.SfUserAccount;
import com.smartfarm360.repository.SfUserAccountRepository;
import com.smartfarm360.dto.SfRequests;
import com.smartfarm360.dto.SfResponses;
import com.smartfarm360.entity.*;
import com.smartfarm360.repository.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

@Service
public class SmartFarmService {
    private final SfTenantRepository tenants;
    private final SfTenantMembershipRepository memberships;
    private final SfFarmRepository farms;
    private final SfLandParcelRepository parcels;
    private final SfUserAccountRepository users;

    public SmartFarmService(SfTenantRepository tenants, SfTenantMembershipRepository memberships, SfFarmRepository farms, SfLandParcelRepository parcels, SfUserAccountRepository users) {
        this.tenants = tenants; this.memberships = memberships; this.farms = farms; this.parcels = parcels; this.users = users;
    }

    @Transactional
    public SfResponses.Tenant createTenant(String email, SfRequests.CreateTenant request) {
        SfUserAccount user = user(email);
        String base = slugify(request.name());
        String slug = base;
        int suffix = 2;
        while (tenants.findBySlugIgnoreCase(slug).isPresent()) slug = base + "-" + suffix++;
        SfTenant tenant = new SfTenant();
        tenant.setName(request.name().trim()); tenant.setSlug(slug);
        tenant.setPreferredLanguage(request.preferredLanguage() == null ? "en" : request.preferredLanguage().toLowerCase(Locale.ROOT));
        tenant = tenants.save(tenant);
        SfTenantMembership membership = new SfTenantMembership();
        membership.setTenant(tenant); membership.setUser(user); membership.setRole("OWNER");
        memberships.save(membership);
        return tenantResponse(tenant, "OWNER");
    }

    @Transactional(readOnly = true)
    public List<SfResponses.Tenant> tenants(String email) {
        user(email);
        return memberships.findByUserEmailIgnoreCaseAndActiveTrueOrderByTenantNameAsc(email).stream()
                .map(m -> tenantResponse(m.getTenant(), m.getRole())).toList();
    }

    @Transactional
    public SfResponses.Farm createFarm(String email, Long tenantId, SfRequests.CreateFarm request) {
        SfTenant tenant = requireTenant(email, tenantId, true);
        SfFarm farm = new SfFarm(); farm.setTenant(tenant); farm.setName(request.name().trim());
        if (request.farmType() != null) farm.setFarmType(request.farmType());
        farm.setVillage(trimToNull(request.village())); farm.setDistrict(trimToNull(request.district()));
        if (request.state() != null && !request.state().isBlank()) farm.setState(request.state().trim());
        return farmResponse(farms.save(farm));
    }

    @Transactional(readOnly = true)
    public List<SfResponses.Farm> farms(String email, Long tenantId) {
        requireTenant(email, tenantId, false);
        return farms.findByTenantIdOrderByNameAsc(tenantId).stream().map(this::farmResponse).toList();
    }

    @Transactional
    public SfResponses.LandParcel createParcel(String email, Long tenantId, Long farmId, SfRequests.CreateLandParcel request) {
        requireTenant(email, tenantId, true);
        SfFarm farm = farms.findByIdAndTenantId(farmId, tenantId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Farm not found in this tenant"));
        SfLandParcel parcel = new SfLandParcel(); parcel.setTenant(farm.getTenant()); parcel.setFarm(farm);
        parcel.setName(request.name().trim()); parcel.setArea(request.area());
        if (request.areaUnit() != null) parcel.setAreaUnit(request.areaUnit());
        parcel.setSoilType(trimToNull(request.soilType())); parcel.setIrrigationType(trimToNull(request.irrigationType()));
        return parcelResponse(parcels.save(parcel));
    }

    @Transactional(readOnly = true)
    public List<SfResponses.LandParcel> parcels(String email, Long tenantId, Long farmId) {
        requireTenant(email, tenantId, false);
        if (farms.findByIdAndTenantId(farmId, tenantId).isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Farm not found in this tenant");
        return parcels.findByTenantIdAndFarmIdOrderByNameAsc(tenantId, farmId).stream().map(this::parcelResponse).toList();
    }

    private SfTenant requireTenant(String email, Long tenantId, boolean write) {
        SfTenantMembership membership = memberships.findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(tenantId, email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));
        if (write && !List.of("OWNER", "ADMIN", "FARM_MANAGER").contains(membership.getRole()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have permission to change farm data");
        return membership.getTenant();
    }

    private SfUserAccount user(String email) {
        if (email == null || email.isBlank()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in is required");
        return users.findByEmailIgnoreCase(email).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User account not found"));
    }
    private SfResponses.Tenant tenantResponse(SfTenant t, String role) { return new SfResponses.Tenant(t.getId(), t.getName(), t.getSlug(), t.getPreferredLanguage(), role, t.getCreatedAt()); }
    private SfResponses.Farm farmResponse(SfFarm f) { return new SfResponses.Farm(f.getId(), f.getTenant().getId(), f.getName(), f.getFarmType(), f.getVillage(), f.getDistrict(), f.getState(), f.getCountry(), f.isActive(), f.getCreatedAt()); }
    private SfResponses.LandParcel parcelResponse(SfLandParcel p) { return new SfResponses.LandParcel(p.getId(), p.getTenant().getId(), p.getFarm().getId(), p.getName(), p.getArea(), p.getAreaUnit(), p.getSoilType(), p.getIrrigationType(), p.isActive(), p.getCreatedAt()); }
    private String slugify(String value) {
        String slug = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.isBlank()) slug = "farm-business";
        return slug.length() > 80 ? slug.substring(0, 80).replaceAll("-$", "") : slug;
    }
    private String trimToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
