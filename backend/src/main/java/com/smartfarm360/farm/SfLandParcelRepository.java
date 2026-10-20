package com.smartfarm360.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SfLandParcelRepository extends JpaRepository<SfLandParcel, Long> {
    List<SfLandParcel> findByTenantIdAndFarmIdOrderByNameAsc(Long tenantId, Long farmId);
    List<SfLandParcel> findByTenantIdAndFarmIdAndActiveTrueOrderByNameAsc(Long tenantId, Long farmId);
    Optional<SfLandParcel> findByIdAndTenantId(Long id, Long tenantId);
}
