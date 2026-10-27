package com.smartfarm360.platform.security;

import com.smartfarm360.platform.rls.RlsScope;
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
    private static final java.util.Set<String> READS = java.util.Set.of("GET", "HEAD");
    private final TenantAccessService access;
    private final com.smartfarm360.platform.admin.SupportAccessService support;

    public TenantContextInterceptor(TenantAccessService access, com.smartfarm360.platform.admin.SupportAccessService support) {
        this.access = access;
        this.support = support;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        RlsScope.clear();
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

        Long supportGrant = null;
        java.util.Optional<TenantAccessService.Access> member = access.resolve(user.id(), tenantId);
        TenantAccessService.Access resolved;
        if (member.isPresent()) {
            resolved = member.get();
        } else {
            // Platform support reads a farm business only under an owner's grant, after the second sign-in step (O7)
            if (!user.platformAdmin() || !user.secondStep()) throw tenantNotFound();
            supportGrant = support.activeGrant(tenantId, user.id()).orElseThrow(TenantContextInterceptor::tenantNotFound);
            resolved = access.supportAccess(user.id(), tenantId).orElseThrow(TenantContextInterceptor::tenantNotFound);
            if (!READS.contains(request.getMethod())) {
                throw ApiException.forbidden("SUPPORT_READ_ONLY", "Support access is read-only.");
            }
        }
        if (!resolved.tenantActive()) {
            throw ApiException.forbidden("TENANT_SUSPENDED", "This farm business is suspended. Contact support.");
        }
        TenantContext context = resolved.context();
        if (resolved.deletionPending() && !READS.contains(request.getMethod()) && !allowedWhileDeletionPending(request)) {
            throw ApiException.forbidden("TENANT_DELETION_PENDING", "This farm business is being deleted and is read-only. An owner can cancel the deletion.");
        }

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
        if (module != null && java.util.Arrays.stream(module.value()).noneMatch(context.enabledModules()::contains)) {
            throw ApiException.forbidden("MODULE_DISABLED", "This module is not enabled for your farm business.");
        }

        request.setAttribute(ATTRIBUTE, context);
        // From here the database itself only shows this farm business's rows (ADR 0011)
        RlsScope.enterTenant(tenantId);
        org.slf4j.MDC.put("tenantId", String.valueOf(tenantId));
        org.slf4j.MDC.put("userId", String.valueOf(user.id()));
        if (supportGrant != null) {
            String query = request.getQueryString();
            support.recordView(tenantId, user.id(), supportGrant, request.getRequestURI() + (query == null ? "" : "?" + query));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RlsScope.clear();
        org.slf4j.MDC.remove("tenantId");
        org.slf4j.MDC.remove("userId");
    }

    /** During the deletion grace an owner can still cancel the deletion and export the business (O10). */
    private static boolean allowedWhileDeletionPending(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.endsWith("/deletion") || path.endsWith("/exports");
    }

    private static ApiException tenantNotFound() {
        return ApiException.notFound("TENANT_NOT_FOUND", "Farm business not found.");
    }
}
