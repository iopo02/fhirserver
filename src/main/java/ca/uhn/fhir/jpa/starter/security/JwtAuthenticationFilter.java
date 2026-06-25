package ca.uhn.fhir.jpa.starter.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT Authentication Filter - validates JWT token and sets Spring Security context
 * 
 * Simplified version that does not depend on a database user lookup.
 * Trust the JWT claims directly.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String token = extractTokenFromRequest(request);

            if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                String username = jwtTokenProvider.getUsernameFromToken(token);
                Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

                if (StringUtils.hasText(username)) {
                    // Convert roles to Spring Security authorities
                    Set<SimpleGrantedAuthority> authorities = roles.stream()
                        .filter(role -> role != null && !role.isEmpty())
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toSet());

                    // Set authentication
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT authentication set for user: {} with roles: {}", username, roles);
                } else {
                    log.warn("No username found in JWT token");
                }
            }
        } catch (Exception ex) {
            log.error("Error processing JWT authentication: {}", ex.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    private String extractTokenFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
