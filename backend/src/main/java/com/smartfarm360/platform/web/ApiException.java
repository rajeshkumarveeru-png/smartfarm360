package com.smartfarm360.platform.web;

import org.springframework.http.HttpStatus;

/**
 * An error with a stable, machine-readable {@code code} that clients translate.
 * The message must be safe to show to users: never include internal details.
 */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String code, String message) { return new ApiException(HttpStatus.NOT_FOUND, code, message); }
    public static ApiException badRequest(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST, code, message); }
    public static ApiException conflict(String code, String message) { return new ApiException(HttpStatus.CONFLICT, code, message); }
    public static ApiException forbidden(String code, String message) { return new ApiException(HttpStatus.FORBIDDEN, code, message); }
    public static ApiException unauthorized(String code, String message) { return new ApiException(HttpStatus.UNAUTHORIZED, code, message); }
}
