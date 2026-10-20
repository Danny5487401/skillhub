package com.iflytek.skillhub.filter;

import com.iflytek.skillhub.auth.policy.RouteSecurityPolicyRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.util.Set;

/**
 * Logs request method, path, status and timing without user-controlled query or header values.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final Set<String> SKIP_PREFIXES = Set.of(
            "/actuator", "/favicon.ico", "/assets/", "/internal/github-sync/",
            "/api/v1/admin/github-sync/credentials"
    );
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String uri = RouteSecurityPolicyRegistry.requestPath(request);
        if (shouldSkip(uri)) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper cachedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper cachedResponse = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();

        try {
            filterChain.doFilter(cachedRequest, cachedResponse);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logRequest(cachedRequest, cachedResponse, duration);
            cachedResponse.copyBodyToResponse();
        }
    }

    private void logRequest(ContentCachingRequestWrapper request, ContentCachingResponseWrapper response, long duration) {
        String requestUri = request.getRequestURI();
        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod()).append(" ").append(requestUri);
        sb.append(" | ").append(response.getStatus());
        sb.append(" | ").append(duration).append("ms");
        sb.append(" | ").append(request.getRemoteAddr());
        log.info(sb.toString());
    }

    private boolean shouldSkip(String uri) {
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

}
