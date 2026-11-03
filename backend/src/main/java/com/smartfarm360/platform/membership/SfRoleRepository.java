package com.smartfarm360.platform.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;

public interface SfRoleRepository extends JpaRepository<SfRole, Long> {
    List<SfRole> findByTenantIdIsNullAndCodeIn(Collection<String> codes);
    List<SfRole> findByTenantIdIsNullOrderById();
}
