package com.smartfarm360.repository;
import com.smartfarm360.entity.SfTenant;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface SfTenantRepository extends JpaRepository<SfTenant, Long> { Optional<SfTenant> findBySlugIgnoreCase(String slug); }
