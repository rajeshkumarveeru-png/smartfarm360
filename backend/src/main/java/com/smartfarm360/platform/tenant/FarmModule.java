package com.smartfarm360.platform.tenant;

/**
 * Optional farming modules a tenant can switch on. Finance, inventory and farm setup are always on.
 * Codes must match the {@code ck_sf_tenant_module_code} check constraint.
 * Only modules with {@code available = true} have a working implementation and may be enabled.
 */
public enum FarmModule {
    CROP(true),
    DAIRY(false),
    POULTRY(false),
    GOAT_SHEEP(false),
    AQUACULTURE(false),
    ORGANIC(false),
    PROCESSING(false),
    WORKFORCE(true),
    ASSETS(true);

    private final boolean available;

    FarmModule(boolean available) { this.available = available; }

    public boolean available() { return available; }
}
