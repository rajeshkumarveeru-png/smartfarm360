package com.smartfarm360.platform.membership;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SfInvitationRepository extends JpaRepository<SfInvitation, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SfInvitation> findByTokenHash(String tokenHash);

    Optional<SfInvitation> findByIdAndTenantId(Long id, Long tenantId);

    @Query("select i from SfInvitation i where i.tenantId = :tenantId and i.acceptedAt is null and i.revokedAt is null and i.expiresAt > :now order by i.createdAt desc")
    List<SfInvitation> findPending(Long tenantId, Instant now);

    @Query("select i from SfInvitation i where i.tenantId = :tenantId and lower(i.email) = lower(:email) and i.acceptedAt is null and i.revokedAt is null")
    List<SfInvitation> findOpenForEmail(Long tenantId, String email);
}
