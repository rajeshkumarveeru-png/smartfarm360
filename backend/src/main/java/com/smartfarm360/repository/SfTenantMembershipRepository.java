package com.smartfarm360.repository;
import com.smartfarm360.entity.SfTenantMembership;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SfTenantMembershipRepository extends JpaRepository<SfTenantMembership, Long> {
    List<SfTenantMembership> findByUserEmailIgnoreCaseAndActiveTrueOrderByTenantNameAsc(String email);
    Optional<SfTenantMembership> findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(Long tenantId, String email);
    Optional<SfTenantMembership> findByTenantIdAndUserEmailIgnoreCase(Long tenantId, String email);
}
