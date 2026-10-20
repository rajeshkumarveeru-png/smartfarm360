package com.smartfarm360.platform.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SfRefreshTokenRepository extends JpaRepository<SfRefreshToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SfRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update SfRefreshToken t set t.revokedAt = :now, t.revokeReason = :reason where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(UUID familyId, String reason, Instant now);

    @Modifying
    @Query("update SfRefreshToken t set t.revokedAt = :now, t.revokeReason = :reason where t.userId = :userId and t.revokedAt is null")
    int revokeAllForUser(Long userId, String reason, Instant now);
}
