package com.smartfarm360.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tenant endpoint whose requests may carry an {@code Idempotency-Key} (mobile offline design, N4): the first
 * request with a key is processed and its successful response kept; a repeat with the same key and body gets the kept
 * response; a repeat with a different body is refused. Used on every endpoint the phone sends to from its outbox.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}
