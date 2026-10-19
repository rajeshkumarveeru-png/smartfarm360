package com.smartfarm360.repository;
import com.smartfarm360.entity.SfLandParcel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SfLandParcelRepository extends JpaRepository<SfLandParcel, Long> {
    List<SfLandParcel> findByTenantIdAndFarmIdOrderByNameAsc(Long tenantId, Long farmId);
}
