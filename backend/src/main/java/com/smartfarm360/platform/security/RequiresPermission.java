package com.smartfarm360.platform.security;

import com.smartfarm360.platform.rbac.Permission;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Required on every handler under {@code /api/v1/tenants/{tenantId}/**}. A tenant handler without it is refused
 * (fail closed), and {@code TenantEndpointInventoryTest} fails the build.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {
    Permission value();
}
