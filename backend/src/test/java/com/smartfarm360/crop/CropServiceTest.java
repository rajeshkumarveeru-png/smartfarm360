package com.smartfarm360.crop;

import com.smartfarm360.crop.CropDtos.SeasonRequest;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit rules of CropService. The former "non-member cannot read crops" case moved to the tenant interceptor and is
 * now covered for every endpoint by CrossTenantIsolationTest.
 */
class CropServiceTest {
    private JdbcTemplate jdbc;
    private CropService service;
    private final TenantContext ctx = new TenantContext(7L, 1L, 1L, Set.of("TENANT_OWNER"),
            EnumSet.allOf(Permission.class), EnumSet.of(FarmModule.CROP));

    @BeforeEach void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new CropService(jdbc);
    }

    @Test void invalidSeasonRangeIsRejectedBeforeDatabaseWrite() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createSeason(ctx, new SeasonRequest("Samba", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-11-01"), null)));
        assertEquals(400, error.getStatusCode().value());
        verifyNoInteractions(jdbc);
    }
}
