package com.smartfarm360.platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfarm360.platform.web.Problems;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 401 and 403 responses from the security filter chain, in the same problem format as the rest of the API. */
@Component
public class ProblemAuthenticationHandlers {
    private final ObjectMapper objectMapper;

    public ProblemAuthenticationHandlers(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, e) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            write(response, Problems.of(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in is required."));
        };
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, e) ->
                write(response, Problems.of(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "You do not have permission to do this."));
    }

    private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
