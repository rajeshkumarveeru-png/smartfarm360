package com.smartfarm360.platform.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Read-only maintenance (O8): while it runs, every change is refused with 503 {@code MAINTENANCE}. Reads, signing in
 * and the platform console still work. The phone keeps its outbox and sends it later (it retries 5xx replies).
 */
@Component
public class MaintenanceFilter extends OncePerRequestFilter {
    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private final AnnouncementService announcements;

    public MaintenanceFilter(AnnouncementService announcements) { this.announcements = announcements; }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return READS.contains(request.getMethod()) || !path.startsWith("/api/") || path.startsWith("/api/v1/platform/") || path.startsWith("/api/v1/auth/")
                || path.startsWith("/api/v1/billing/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (!announcements.maintenanceActive()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(503);
        response.setHeader("Retry-After", "600");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write("{\"status\":503,\"code\":\"MAINTENANCE\",\"detail\":\"SmartFarm360 is read-only for maintenance. Try again later.\"}"
                .getBytes(StandardCharsets.UTF_8));
    }
}
