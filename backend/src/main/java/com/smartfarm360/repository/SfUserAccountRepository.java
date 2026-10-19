package com.smartfarm360.repository;

import com.smartfarm360.entity.SfUserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SfUserAccountRepository extends JpaRepository<SfUserAccount, Long> {
    Optional<SfUserAccount> findByEmailIgnoreCase(String email);
    boolean existsByEmailIgnoreCase(String email);
}
