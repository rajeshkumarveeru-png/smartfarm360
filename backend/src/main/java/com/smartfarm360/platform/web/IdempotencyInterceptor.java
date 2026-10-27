package com.smartfarm360.platform.web;

import com.smartfarm360.platform.security.TenantContext;
import com.smartfarm360.platform.security.TenantContextInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Replays or claims {@code Idempotency-Key}s on {@link Idempotent} endpoints (mobile offline design, N4). It runs after the
 * tenant check, so a key is only ever honoured for a member of the farm business, and only for the user who first sent it.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {
    static final String CLAIM = IdempotencyInterceptor.class.getName() + ".claim";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._:-]{8,100}");
    private final IdempotencyStore store;

    public IdempotencyInterceptor(IdempotencyStore store) { this.store = store; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(Idempotent.class)) return true;
        String key = request.getHeader(IdempotencyFilter.HEADER);
        if (key == null) return true;
        if (!SAFE.matcher(key).matches()) throw ApiException.badRequest("IDEMPOTENCY_KEY_INVALID", "The Idempotency-Key is not valid.");
        TenantContext ctx = (TenantContext) request.getAttribute(TenantContextInterceptor.ATTRIBUTE);
        IdempotencyFilter.CachedBodyRequest cached = unwrap(request);
        if (ctx == null || cached == null) return true;
        String hash = hash(request.getMethod(), request.getRequestURI(), cached.body());

        for (int attempt = 0; attempt < 2; attempt++) {
            Optional<Long> claimed = store.claim(ctx.tenantId(), ctx.userId(), key, request.getMethod(), request.getRequestURI(), hash);
            if (claimed.isPresent()) {
                request.setAttribute(CLAIM, claimed.get());
                return true;
            }
            Optional<IdempotencyStore.Stored> stored = store.find(ctx.tenantId(), key);
            if (stored.isEmpty()) continue;   // released between the two statements: try to claim again
            IdempotencyStore.Stored s = stored.get();
            if (s.userId() != ctx.userId() || !s.requestHash().equals(hash)) {
                throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request.");
            }
            if (s.status() == null) throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "This request is already being processed.");
            response.setStatus(s.status());
            if (s.contentType() != null) response.setContentType(s.contentType());
            response.setHeader("Idempotent-Replayed", "true");
            io.micrometer.core.instrument.Metrics.counter("sf.sync.replays").increment();
            if (s.body() != null) response.getOutputStream().write(s.body().getBytes(StandardCharsets.UTF_8));
            return false;
        }
        throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS", "This request is already being processed.");
    }

    /** Finds the cached-body request under any wrappers added after the filter. */
    private static IdempotencyFilter.CachedBodyRequest unwrap(jakarta.servlet.ServletRequest request) {
        jakarta.servlet.ServletRequest r = request;
        while (r != null) {
            if (r instanceof IdempotencyFilter.CachedBodyRequest c) return c;
            r = r instanceof jakarta.servlet.ServletRequestWrapper w ? w.getRequest() : null;
        }
        return null;
    }

    static String hash(String method, String path, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((method + " " + path + "\n").getBytes(StandardCharsets.UTF_8));
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
