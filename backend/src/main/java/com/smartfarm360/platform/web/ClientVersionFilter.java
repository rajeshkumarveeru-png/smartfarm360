package com.smartfarm360.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logs the phone app's version ({@code X-Client-Version}) and refuses versions older than {@code app.min-client-version},
 * so an old outbox is not sent in a format the server no longer accepts (mobile offline design, N14). Requests without the
 * header (the web app, scripts) are not affected.
 */
@Component
public class ClientVersionFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Client-Version";
    private static final Pattern VERSION = Pattern.compile("\\d{1,4}(\\.\\d{1,4}){0,2}");
    private final String minimum;

    public ClientVersionFilter(@Value("${app.min-client-version:}") String minimum) { this.minimum = minimum == null ? "" : minimum.trim(); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String version = request.getHeader(HEADER);
        if (version == null || !VERSION.matcher(version.trim()).matches()) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put("clientVersion", version.trim());
        try {
            if (!minimum.isEmpty() && compare(version.trim(), minimum) < 0) {
                response.setStatus(426);
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                response.getOutputStream().write(("{\"status\":426,\"code\":\"CLIENT_UPGRADE_REQUIRED\",\"detail\":\"Please update the SmartFarm360 app.\"}")
                        .getBytes(StandardCharsets.UTF_8));
                return;
            }
            chain.doFilter(request, response);
        } finally {
            MDC.remove("clientVersion");
        }
    }

    /** Compares dotted versions numerically: 1.10 is newer than 1.9. */
    static int compare(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? Integer.parseInt(x[i]) : 0, q = i < y.length && !y[i].isBlank() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }
}
