package com.smartfarm360.platform.security;

import com.smartfarm360.platform.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

/**
 * Establishes {@link TenantContext} for every handler whose path contains {@code {tenantId}}.
 * Runs before request bodies are read, so cross-tenant requests are refused before any input is processed.
 * <ul>
 *   <li>No active membership (or a malformed id) → 404 TENANT_NOT_FOUND, so tenant existence is not revealed.</li>
 *   <li>Suspended tenant → 403 TENANT_SUSPENDED.</li>
 *   <li>Missing {@link RequiresPermission} → refused (fail closed).</li>
 * </ul>
 */
@Component
public class TenantContextInterceptor implements HandlerInterceptor {
    public static final String ATTRIBUTE = TenantContext.class.getName();
    private static final Logger log = LoggerFactory.getLogger(TenantContextInterceptor.class);
    private final TenantAccessService access;

    public TenantContextInterceptor(TenantAccessService access) { this.access = access; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        @SuppressWarnings("unchecked")
        Map<String, String> vars = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (vars == null || !vars.containsKey("tenantId")) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth != null && auth.getPrincipal() instanceof CurrentUser user)) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Sign in is required.");
        }
        long tenantId;
        try {
            tenantId = Long.parseLong(vars.get("tenantId"));
        } catch (NumberFormatException e) {
            throw tenantNotFound();
        }

        TenantAccessService.Access resolved = access.resolve(user.id(), tenantId).orElseThrow(TenantContextInterceptor::tenantNotFound);
        if (!resolved.tenantActive()) {
            throw ApiException.forbidden("TENANT_SUSPENDED", "This farm business is suspended. Contact support.");
        }
        TenantContext context = resolved.context();

        RequiresPermission permission = method.getMethodAnnotation(RequiresPermission.class);
        if (permission == null) {
            log.error("Tenant endpoint {} has no @RequiresPermission; refusing request", method.getShortLogMessage());
            throw ApiException.forbidden("ACCESS_DENIED", "You do not have permission to do this.");
        }
        if (!context.has(permission.value())) {
            throw ApiException.forbidden("ACCESS_DENIED", "You do not have permission to do this.");
        }

        RequiresModule module = method.getMethodAnnotation(RequiresModule.class);
        if (module == null) module = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RequiresModule.class);
        if (module != null && !context.enabledModules().contains(module.value())) {
            throw ApiException.forbidden("MODULE_DISABLED", "This module is not enabled for your farm business.");
        }

        request.setAttribute(ATTRIBUTE, context);
        return true;
    }

    private static ApiException tenantNotFound() {
        return ApiException.notFound("TENANT_NOT_FOUND", "Farm business not found.");
    }
}
