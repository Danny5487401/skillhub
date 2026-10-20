package com.iflytek.skillhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;

/** A dedicated, stateless machine-identity boundary for the Go acquisition worker. */
@Configuration
public class GithubSyncInternalSecurityConfig {
    @Bean
    @Order(-1)
    public SecurityFilterChain githubSyncInternalFilterChain(
            HttpSecurity http,
            @Value("${skillhub.github-sync.service-tokens:}") String configuredTokens) throws Exception {
        http.securityMatcher("/internal/github-sync/v1/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, error) ->
                        reject(response)))
                .addFilterBefore(new ServiceTokenFilter(configuredTokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"AUTH_INVALID\",\"message\":\"Invalid service identity\"}");
    }

    private static final class ServiceTokenFilter extends OncePerRequestFilter {
        private final List<byte[]> tokenHashes;

        private ServiceTokenFilter(String configuredTokens) {
            tokenHashes = Arrays.stream(configuredTokens.split(",", -1))
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .map(token -> {
                        if (token.length() < 32) {
                            throw new IllegalArgumentException("GitHub sync service tokens must have at least 32 characters");
                        }
                        return hash(token);
                    })
                    .toList();
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")
                    || authorization.length() <= "Bearer ".length()) {
                reject(response);
                return;
            }
            byte[] suppliedHash = hash(authorization.substring("Bearer ".length()));
            boolean matched = false;
            for (byte[] expectedHash : tokenHashes) {
                matched |= MessageDigest.isEqual(expectedHash, suppliedHash);
            }
            if (!matched) {
                reject(response);
                return;
            }
            var authentication = new UsernamePasswordAuthenticationToken(
                    "github-sync-worker", null,
                    List.of(new SimpleGrantedAuthority("ROLE_GITHUB_SYNC_WORKER")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            chain.doFilter(request, response);
        }

        private static byte[] hash(String token) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable", exception);
            }
        }
    }
}
