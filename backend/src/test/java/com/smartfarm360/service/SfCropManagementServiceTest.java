package com.smartfarm360.service;

import com.smartfarm360.entity.SfTenantMembership;
import com.smartfarm360.repository.SfTenantMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SfCropManagementServiceTest {
    private JdbcTemplate jdbc;
    private SfTenantMembershipRepository memberships;
    private SfCropManagementService service;

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        memberships = mock(SfTenantMembershipRepository.class);
        service = new SfCropManagementService(jdbc, memberships);
    }

    @Test void tenantWithoutMembershipCannotReadCropMaster() {
        when(memberships.findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(44L, "user@example.com"))
                .thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.crops("user@example.com", 44L));
        assertEquals(404, error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }

    @Test void invalidSeasonRangeIsRejectedBeforeDatabaseWrite() {
        SfTenantMembership membership = mock(SfTenantMembership.class);
        when(membership.getRole()).thenReturn("OWNER");
        when(memberships.findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(7L, "owner@example.com"))
                .thenReturn(Optional.of(membership));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createSeason("owner@example.com", 7L, Map.of(
                        "name", "Samba", "startDate", "2026-12-01", "endDate", "2026-11-01")));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
