package com.smartfarm360.crop;

import com.smartfarm360.costing.CostObjectService;
import com.smartfarm360.crop.CropDtos.SeasonRequest;
import com.smartfarm360.platform.audit.AuditService;
import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.tenant.FarmModule;
import com.smartfarm360.platform.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit rules of CropService. The former "non-member cannot read crops" case moved to the tenant interceptor and is
 * now covered for every endpoint by CrossTenantIsolationTest; crop behaviour is covered end to end by CropRulesTest.
 */
class CropServiceTest {
    private JdbcClient jdbc;
    private CropService service;
    private final TenantContext ctx = new TenantContext(7L, 1L, 1L, Set.of("TENANT_OWNER"),
            EnumSet.allOf(Permission.class), EnumSet.of(FarmModule.CROP));

    @BeforeEach void setUp() {
        jdbc = mock(JdbcClient.class);
        service = new CropService(jdbc, mock(CostObjectService.class), mock(AuditService.class));
    }

    @Test void invalidSeasonRangeIsRejectedBeforeDatabaseWrite() {
        ApiException error = assertThrows(ApiException.class,
                () -> service.createSeason(ctx, new SeasonRequest("Samba", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-11-01"), null)));
        assertEquals(400, error.status().value());
        assertEquals("INVALID_DATE_RANGE", error.code());
        verifyNoInteractions(jdbc);
    }

    @Test void areasConvertToAcres() {
        assertEquals(0, new BigDecimal("2.4711").compareTo(CropService.toAcres(BigDecimal.ONE, "HECTARE")));
        assertEquals(0, new BigDecimal("0.5000").compareTo(CropService.toAcres(new BigDecimal("50"), "CENT")));
        assertEquals(0, new BigDecimal("1.0000").compareTo(CropService.toAcres(new BigDecimal("43560"), "SQFT")));
        assertNull(CropService.toAcres(new BigDecimal("0.1"), "SQFT"));
    }
}
