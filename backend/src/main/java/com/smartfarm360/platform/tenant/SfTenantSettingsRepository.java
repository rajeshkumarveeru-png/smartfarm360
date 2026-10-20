package com.smartfarm360.platform.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SfTenantSettingsRepository extends JpaRepository<SfTenantSettings, Long> {
}
