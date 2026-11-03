package com.smartfarm360.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SfFarmRepository extends JpaRepository<SfFarm, Long> {
    List<SfFarm> findByTenantIdOrderByNameAsc(Long tenantId);
    List<SfFarm> findByTenantIdAndActiveTrueOrderByNameAsc(Long tenantId);
    Optional<SfFarm> findByIdAndTenantId(Long id, Long tenantId);
}
