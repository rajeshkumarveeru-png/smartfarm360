package com.smartfarm360.platform.security;

import com.smartfarm360.platform.tenant.FarmModule;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Refuses the request with 403 MODULE_DISABLED unless the tenant has enabled one of these modules. Allowed on types and
 * methods; a method annotation replaces the type's (for example livestock endpoints that only dairy uses).
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresModule {
    FarmModule[] value();
}
