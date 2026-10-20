package com.smartfarm360.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns every error into an RFC 7807 problem with a stable {@code code}. Server errors never expose details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Problems.of(e.status(), e.code(), e.getMessage()));
    }

    /** Legacy services still throw ResponseStatusException with author-written, user-safe reasons. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> responseStatus(ResponseStatusException e) {
        int status = e.getStatusCode().value();
        String code = status == 400 ? "VALIDATION_FAILED" : Problems.defaultCode(status);
        String detail = status >= 500 ? "Something went wrong. Please try again." : e.getReason();
        return ResponseEntity.status(e.getStatusCode()).body(Problems.of(e.getStatusCode(), code, detail));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(Problems.validation(List.of(fieldError(e.getName(), "INVALID_VALUE", "Invalid value"))));
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Problems.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to do this."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException e) {
        log.info("Data integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Problems.of(HttpStatus.CONFLICT, "CONFLICT", "This change conflicts with existing data."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong. Please try again."));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> fieldError(f.getField(), codeFor(f), f.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(Problems.validation(errors));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex, HttpHeaders headers,
                                                                            HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream().map(err -> fieldError(
                        r.getMethodParameter().getParameterName(), "INVALID_VALUE", err.getDefaultMessage())))
                .toList();
        return ResponseEntity.badRequest().body(Problems.validation(errors));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String detail = status.is5xxServerError() ? "Something went wrong. Please try again."
                : body instanceof ProblemDetail p && p.getDetail() != null ? p.getDetail() : "The request could not be processed.";
        if (status.is5xxServerError()) log.error("Request failed", ex);
        return new ResponseEntity<>(Problems.of(status, Problems.defaultCode(status.value()), detail), headers, status);
    }

    private static String codeFor(FieldError error) {
        String constraint = error.getCode();
        if (constraint == null) return "INVALID_VALUE";
        return switch (constraint) {
            case "NotBlank", "NotNull", "NotEmpty" -> "REQUIRED";
            case "Size" -> "INVALID_LENGTH";
            case "Email" -> "INVALID_EMAIL";
            case "DecimalMin", "Min", "Positive", "PositiveOrZero" -> "TOO_SMALL";
            case "DecimalMax", "Max" -> "TOO_LARGE";
            case "Pattern" -> "INVALID_FORMAT";
            default -> "INVALID_VALUE";
        };
    }

    private static Map<String, String> fieldError(String field, String code, String message) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("code", code);
        m.put("message", message == null ? "Invalid value" : message);
        return m;
    }
}
