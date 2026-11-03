package com.smartfarm360.platform.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.time.Instant;
import java.util.Optional;

public interface SfPasswordResetTokenRepository extends JpaRepository<SfPasswordResetToken, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<SfPasswordResetToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update SfPasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateOpenTokens(Long userId, Instant now);
}
