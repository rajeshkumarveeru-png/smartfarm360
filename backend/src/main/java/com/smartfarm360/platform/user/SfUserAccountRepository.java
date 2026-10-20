package com.smartfarm360.platform.user;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SfUserAccountRepository extends JpaRepository<SfUserAccount, Long> {
    Optional<SfUserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
