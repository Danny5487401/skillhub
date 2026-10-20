package com.iflytek.skillhub.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Logs the request path without user-controlled query parameter names or values.
 */
@Component
public class SensitiveLogSanitizer {

    public String sanitizeRequestTarget(HttpServletRequest request) {
        return request.getRequestURI();
    }
}
