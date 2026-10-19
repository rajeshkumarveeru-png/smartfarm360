package com.smartfarm360.repository;
import com.smartfarm360.entity.SfFarm;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SfFarmRepository extends JpaRepository<SfFarm, Long> {
    List<SfFarm> findByTenantIdOrderByNameAsc(Long tenantId);
    Optional<SfFarm> findByIdAndTenantId(Long id, Long tenantId);
}
