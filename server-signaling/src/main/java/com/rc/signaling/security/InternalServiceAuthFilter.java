package com.rc.signaling.security;

import com.rc.signaling.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Authenticates internal service calls; deploy mTLS in front of this application-layer gate. */
public final class InternalServiceAuthFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-RC-Internal-Token";
    private final byte[] expected;

    public InternalServiceAuthFilter(SecurityProperties properties) {
        this.expected = properties.getInternalServiceToken().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/internal/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        if (supplied != null && MessageDigest.isEqual(expected, supplied.getBytes(StandardCharsets.UTF_8))) {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("internal-service", null,
                            List.of(new SimpleGrantedAuthority("SCOPE_internal"))));
        }
        filterChain.doFilter(request, response);
    }
}
