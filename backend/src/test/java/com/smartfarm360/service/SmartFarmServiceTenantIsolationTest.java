package com.smartfarm360.service;

import com.smartfarm360.entity.SfUserAccount;
import com.smartfarm360.repository.SfUserAccountRepository;
import com.smartfarm360.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SmartFarmServiceTenantIsolationTest {
    private SfTenantRepository tenants;
    private SfTenantMembershipRepository memberships;
    private SfFarmRepository farms;
    private SfLandParcelRepository parcels;
    private SfUserAccountRepository users;
    private SmartFarmService service;

    @BeforeEach void setUp() {
        tenants = mock(SfTenantRepository.class);
        memberships = mock(SfTenantMembershipRepository.class);
        farms = mock(SfFarmRepository.class);
        parcels = mock(SfLandParcelRepository.class);
        users = mock(SfUserAccountRepository.class);
        service = new SmartFarmService(tenants, memberships, farms, parcels, users);
        SfUserAccount account = new SfUserAccount(); account.setId(10L); account.setEmail("owner@example.com");
        when(users.findByEmailIgnoreCase("owner@example.com")).thenReturn(Optional.of(account));
    }

    @Test void userCannotListFarmsForTenantWithoutMembership() {
        when(memberships.findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(99L, "owner@example.com"))
                .thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.farms("owner@example.com", 99L));
        assertEquals(404, error.getStatusCode().value());
        verify(farms, never()).findByTenantIdOrderByNameAsc(anyLong());
    }

    @Test void userCannotAccessLandForFarmOutsideTheirTenant() {
        when(memberships.findByTenantIdAndUserEmailIgnoreCaseAndActiveTrue(5L, "owner@example.com"))
                .thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.parcels("owner@example.com", 5L, 123L));
        assertEquals(404, error.getStatusCode().value());
        verify(farms, never()).findByIdAndTenantId(anyLong(), anyLong());
        verify(parcels, never()).findByTenantIdAndFarmIdOrderByNameAsc(anyLong(), anyLong());
    }
}
