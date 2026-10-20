package com.smartfarm360.platform.web;

import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import java.util.List;
import java.util.Map;

/** Builds RFC 7807 problem details with SmartFarm360's {@code code}, {@code correlationId} and {@code errors} fields. */
public final class Problems {
    private Problems() {}

    public static ProblemDetail of(HttpStatusCode status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) problem.setProperty("correlationId", correlationId);
        return problem;
    }

    public static ProblemDetail validation(List<Map<String, String>> fieldErrors) {
        ProblemDetail problem = of(HttpStatusCode.valueOf(400), "VALIDATION_FAILED", "The request contains invalid values.");
        problem.setProperty("errors", fieldErrors);
        return problem;
    }

    /** Default code for a status when no specific code applies. */
    public static String defaultCode(int status) {
        return switch (status) {
            case 400 -> "BAD_REQUEST";
            case 401 -> "UNAUTHENTICATED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 409 -> "CONFLICT";
            case 413 -> "PAYLOAD_TOO_LARGE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }
}
