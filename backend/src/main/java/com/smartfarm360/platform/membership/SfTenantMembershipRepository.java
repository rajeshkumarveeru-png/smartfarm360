package com.smartfarm360.platform.membership;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface SfTenantMembershipRepository extends JpaRepository<SfTenantMembership, Long> {
    @Query("select m from SfTenantMembership m join fetch m.tenant t where m.user.id = :userId and m.active = true order by t.name")
    List<SfTenantMembership> findActiveByUserId(Long userId);

    @Query("select distinct m from SfTenantMembership m join fetch m.user left join fetch m.roles where m.tenant.id = :tenantId and m.active = true order by m.id")
    List<SfTenantMembership> findActiveByTenantId(Long tenantId);

    Optional<SfTenantMembership> findByIdAndTenantId(Long id, Long tenantId);

    Optional<SfTenantMembership> findByTenantIdAndUserId(Long tenantId, Long userId);

    @Query("select count(distinct m.id) from SfTenantMembership m join m.roles r where m.tenant.id = :tenantId and m.active = true and r.code = 'TENANT_OWNER'")
    long countActiveOwners(Long tenantId);
}
