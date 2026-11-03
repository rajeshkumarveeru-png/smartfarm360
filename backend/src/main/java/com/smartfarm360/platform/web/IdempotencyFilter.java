package com.smartfarm360.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Wraps tenant requests that carry an {@code Idempotency-Key} so their body can be hashed before the controller reads it,
 * and their response kept once the {@link IdempotencyInterceptor} has claimed the key (mobile offline design, N4).
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String HEADER = "Idempotency-Key";
    static final int MAX_BODY = 1_000_000;

    private final IdempotencyStore store;

    public IdempotencyFilter(IdempotencyStore store) { this.store = store; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String method = request.getMethod();
        return request.getHeader(HEADER) == null || "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)
                || !request.getRequestURI().startsWith("/api/v1/tenants/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        ContentCachingResponseWrapper caching = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(wrapped, caching);
        } finally {
            Object claim = wrapped.getAttribute(IdempotencyInterceptor.CLAIM);
            if (claim instanceof Long id) {
                int status = caching.getStatus();
                if (status >= 200 && status < 300) {
                    store.complete(id, status, caching.getContentType(), new String(caching.getContentAsByteArray(), StandardCharsets.UTF_8));
                } else {
                    store.release(id);   // a refused or failed request can be fixed and sent again with the same key
                }
            }
            caching.copyBodyToResponse();
        }
    }

    /** A request whose body was read once and can be read again. */
    static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        byte[] body() { return body; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
