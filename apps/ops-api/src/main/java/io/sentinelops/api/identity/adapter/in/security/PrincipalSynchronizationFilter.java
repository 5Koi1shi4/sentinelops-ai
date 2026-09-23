package io.sentinelops.api.identity.adapter.in.security;

import io.sentinelops.api.identity.application.PrincipalSynchronizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

public class PrincipalSynchronizationFilter extends OncePerRequestFilter {
    private final PrincipalSynchronizer synchronizer;

    public PrincipalSynchronizationFilter(PrincipalSynchronizer synchronizer) {
        this.synchronizer = synchronizer;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && token.isAuthenticated()
                && token.getAuthorities().stream().anyMatch(authority ->
                        authority.getAuthority().equals(SentinelJwtAuthenticationConverter.API_AUTHORITY))) {
            synchronizer.synchronize(token.getToken());
        }
        chain.doFilter(request, response);
    }
}
