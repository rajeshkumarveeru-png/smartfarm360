package com.smartfarm360.platform.tenant;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SfTenantRepository extends JpaRepository<SfTenant, Long> {
    Optional<SfTenant> findBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCase(String slug);
}
