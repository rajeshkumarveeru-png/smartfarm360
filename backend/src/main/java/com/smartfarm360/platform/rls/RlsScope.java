package com.smartfarm360.platform.rls;

/**
 * The Row-Level Security scope of the current thread (ADR 0011): a farm business's id while a request under
 * {@code /tenants/{tenantId}} runs, otherwise "all" (sign-in, the business list, the platform console, background jobs).
 * Every connection borrowed from the pool is given this scope, so the database refuses rows of any other business.
 */
public final class RlsScope {
    public static final String ALL = "all";
    private static final ThreadLocal<Long> TENANT = new ThreadLocal<>();

    private RlsScope() {}

    public static void enterTenant(long tenantId) { TENANT.set(tenantId); }

    public static void clear() { TENANT.remove(); }

    /** The value for {@code app.tenant_id}. */
    public static String current() {
        Long t = TENANT.get();
        return t == null ? ALL : String.valueOf(t);
    }
}
