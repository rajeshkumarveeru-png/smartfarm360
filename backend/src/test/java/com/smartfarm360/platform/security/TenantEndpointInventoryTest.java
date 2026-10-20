package com.smartfarm360.platform.security;

import com.smartfarm360.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Fails the build when an endpoint is added outside the tenancy and permission rules (ADR 0002). */
@SpringBootTest
class TenantEndpointInventoryTest extends EmbeddedPostgresSupport {
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;

    @Test
    void everyTenantEndpointDeclaresAPermission() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            boolean tenantScoped = e.getKey().getPatternValues().stream().anyMatch(p -> p.contains("{tenantId}"));
            if (tenantScoped && !e.getValue().hasMethodAnnotation(RequiresPermission.class)) missing.add(e.getKey().toString());
        }
        assertThat(missing).as("tenant endpoints without @RequiresPermission").isEmpty();
    }

    @Test
    void applicationEndpointsAreVersionedAndTenantDataLivesUnderATenantPath() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
            String controller = e.getValue().getBeanType().getName();
            if (!controller.startsWith("com.smartfarm360.")) continue;
            for (String pattern : e.getKey().getPatternValues()) {
                if (!pattern.startsWith("/api/v1/")) offenders.add(pattern + " (not under /api/v1)");
                boolean farmData = controller.startsWith("com.smartfarm360.farm.") || controller.startsWith("com.smartfarm360.crop.")
                        || controller.startsWith("com.smartfarm360.finance.") || controller.startsWith("com.smartfarm360.inventory.");
                if (farmData && !pattern.startsWith("/api/v1/tenants/{tenantId}/")) offenders.add(pattern + " (farm data outside tenant path)");
            }
        }
        assertThat(offenders).isEmpty();
    }
}
