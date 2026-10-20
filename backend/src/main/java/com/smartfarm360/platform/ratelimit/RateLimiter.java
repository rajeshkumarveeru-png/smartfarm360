package com.smartfarm360.platform.ratelimit;

import com.smartfarm360.platform.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window, in-memory rate limiter for authentication endpoints.
 * Limits are per application instance; a shared store is needed before running several instances.
 */
@Component
public class RateLimiter {
    private static final int MAX_KEYS = 50_000;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() { this(Clock.systemUTC()); }

    RateLimiter(Clock clock) { this.clock = clock; }

    /** Throws 429 RATE_LIMITED when {@code key} has been used more than {@code limit} times in {@code window}. */
    public void check(String key, int limit, Duration window) {
        if (!tryAcquire(key, limit, window)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please wait and try again.");
        }
    }

    boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        if (windows.size() > MAX_KEYS) windows.values().removeIf(w -> w.expiresAt <= now);
        Window w = windows.compute(key, (k, existing) ->
                existing == null || existing.expiresAt <= now ? new Window(now + window.toMillis()) : existing);
        synchronized (w) {
            w.count++;
            return w.count <= limit;
        }
    }

    public void reset() { windows.clear(); }

    private static final class Window {
        final long expiresAt;
        int count;
        Window(long expiresAt) { this.expiresAt = expiresAt; }
    }
}
