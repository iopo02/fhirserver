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
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

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

        // CORREÇÃO CORS DEFINITIVA: Deixar passar requisições OPTIONS (Preflight) do Frontend
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String token = getJwtFromRequest(request);

        if (StringUtils.hasText(token)) {
            // 1. Valida a assinatura, tempo de expiração e se está na Blacklist
            if (jwtTokenProvider.validateToken(token)) {
                
                // 2. CORREÇÃO DA INVERSÃO: Bloquear se for um Refresh Token a tentar aceder à API
                String tokenType = jwtTokenProvider.getTokenTypeFromToken(token);
                if ("refresh".equalsIgnoreCase(tokenType)) {
                    sendUnauthorized(response, "Este token é um Refresh Token e não pode ser utilizado para aceder à API. Use o Access Token.");
                    return;
                }

                String username = jwtTokenProvider.getUsernameFromToken(token);
                Set<String> roles = jwtTokenProvider.getRolesFromToken(token);

                if (username != null) {
                    // Transforma as Strings de roles ("ADMIN", "MEDICO") em GrantedAuthority ("ROLE_ADMIN", "ROLE_MEDICO")
                    var authorities = roles.stream()
                            .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                            .collect(Collectors.toList());

                    UsernamePasswordAuthenticationToken authentication = 
                            new UsernamePasswordAuthenticationToken(username, null, authorities);
                    
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } else {
                sendUnauthorized(response, "O Token JWT está expirado, é inválido ou foi revogado.");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"success\":false,\"status\":401,\"error\":\"Não Autorizado\",\"message\":\"" + message + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getRequestURI();
        return path.equals("/") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.startsWith("/images/") ||
               path.startsWith("/fonts/") ||
               path.startsWith("/static/") ||
               path.startsWith("/public/") ||
               path.startsWith("/resources/") ||
               path.contains("/auth/login") ||
               path.contains("/auth/refresh") ||
               path.startsWith("/webjars/") ||
               path.equals("/health");
    }
}