package com.smartfarm360.platform.rbac;

/**
 * Tenant-level permissions. Codes must match the rows seeded into {@code sf_permissions}
 * (changeset 029); a migration test enforces this.
 */
public enum Permission {
    TENANT_READ,
    TENANT_MANAGE,
    MEMBERS_READ,
    MEMBERS_MANAGE,
    AUDIT_READ,
    FARM_READ,
    FARM_WRITE,
    CROP_READ,
    CROP_WRITE,
    CROP_ACTIVITY_WRITE,
    FINANCE_READ,
    FINANCE_WRITE,
    FINANCE_REVERSE,
    FINANCE_MANAGE,
    INVENTORY_READ,
    INVENTORY_WRITE,
    WORKFORCE_READ,
    WORKFORCE_WRITE,
    PAYROLL_MANAGE,
    ASSETS_READ,
    ASSETS_WRITE,
    LIVESTOCK_READ,
    LIVESTOCK_WRITE,
    AQUACULTURE_READ,
    AQUACULTURE_WRITE,
    ORGANIC_READ,
    ORGANIC_WRITE
}
