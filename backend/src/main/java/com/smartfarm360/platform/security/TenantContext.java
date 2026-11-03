package com.smartfarm360.platform.security;

import com.smartfarm360.platform.rbac.Permission;
import com.smartfarm360.platform.tenant.FarmModule;

import java.util.Set;

/**
 * The validated tenant scope of a request: built by {@link TenantContextInterceptor} only after confirming an active
 * membership. Services take this instead of a client-supplied tenant id.
 */
public record TenantContext(long tenantId, long userId, long membershipId, Set<String> roleCodes,
                            Set<Permission> permissions, Set<FarmModule> enabledModules) {
    public boolean has(Permission permission) { return permissions.contains(permission); }
    public boolean hasRole(String roleCode) { return roleCodes.contains(roleCode); }
}
