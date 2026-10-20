package com.smartfarm360.platform.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SfTenantModuleRepository extends JpaRepository<SfTenantModule, SfTenantModule.Key> {
    List<SfTenantModule> findByTenantId(Long tenantId);
}
