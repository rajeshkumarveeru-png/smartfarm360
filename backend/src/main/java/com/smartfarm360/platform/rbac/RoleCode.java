package com.smartfarm360.platform.rbac;

/** System roles seeded into {@code sf_roles} (changeset 029). Platform administration is not a tenant role. */
public enum RoleCode {
    TENANT_OWNER,
    FARM_ADMIN,
    FARM_MANAGER,
    ACCOUNTANT,
    FIELD_SUPERVISOR,
    WORKER,
    VIEWER
}
