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
 * Funciona em endpoints protegidos (/fhir/*, /auth/*, /actuator/*)
 * Não filtra recursos estáticos (HTML, CSS, JS) ou raiz (/)
 * 
 * - Se token é válido: autentica o utilizador
 * - Se token é inválido/revogado e endpoint requer auth: retorna 401
 * - Se token é válido mas não autorizado para ação: retorna 403 (via @PreAuthorize)
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

            if (StringUtils.hasText(token)) {
                // Token foi fornecido — validar obrigatoriamente
                if (!jwtTokenProvider.validateToken(token)) {
                    // Token inválido, expirado ou revogado
                    log.warn("Invalid or revoked JWT token from: {}", request.getRemoteAddr());
                    sendUnauthorized(response, "Invalid or revoked token");
                    return;
                }

                // Token válido — extrair claims e autenticar
                String username = jwtTokenProvider.getUsernameFromToken(token);
                Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

                if (StringUtils.hasText(username)) {
                    // Converter roles para Spring Security authorities
                    Set<SimpleGrantedAuthority> authorities = roles.stream()
                        .filter(role -> role != null && !role.isEmpty())
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .collect(Collectors.toSet());

                    // Definir autenticação no contexto
                    UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(username, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(authentication);

                    log.debug("JWT authenticated: user={}, roles={}", username, roles);
                } else {
                    log.warn("No username in JWT token");
                    sendUnauthorized(response, "Invalid token: no username");
                    return;
                }
            }
            // Se não há token, continua sem autenticação
            // Spring Security com @PreAuthorize vai retornar 403 se necessário

        } catch (Exception ex) {
            log.error("JWT filter error: {}", ex.getMessage());
            sendUnauthorized(response, "Authentication error");
            return;
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

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\",\"status\":401}");
    }

    /**
     * Aplica este filtro apenas em endpoints protegidos, não em recursos estáticos
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        
        // Não filtrar raiz, recursos estáticos, ou endpoints públicos
        return path.equals("/") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.startsWith("/images/") ||
               path.startsWith("/fonts/") ||
               path.startsWith("/static/") ||
               path.startsWith("/public/") ||
               path.equals("/auth/login") ||
               path.equals("/auth/refresh") ||
               path.equals("/auth/logout") ||
               path.startsWith("/webjars/") ||
               path.equals("/health");
    }
}
