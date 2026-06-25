package ca.uhn.fhir.jpa.starter.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT Token Provider - generates, validates and extracts claims from JWT tokens
 * 
 * Melhorias implementadas:
 * - Access token curto (15 min) + Refresh token longo (7 dias)
 * - JTI (JWT ID) único para cada token - permite revogação
 * - Claims padrão OIDC: iss, aud, iat, nbf
 * - Roles como array JSON (compatível com standards)
 * - Validação de blacklist (logout imediato)
 */
@Component
@Slf4j
public class JwtTokenProvider {

    @Value("${jwt.secret:fhirserver_secret_key_that_should_be_changed_in_production}")
    private String jwtSecret;

    @Value("${jwt.expiration:86400000}")
    private long jwtExpiration; // 24 hours in milliseconds (mantém compatibilidade)

    @Value("${jwt.access-token-expiration:900000}")
    private long accessTokenExpiration; // 15 minutos = 900.000 ms

    @Value("${jwt.refresh-token-expiration:604800000}")
    private long refreshTokenExpiration; // 7 dias = 604.800.000 ms

    @Value("${jwt.issuer:fhirserver}")
    private String issuer;

    @Value("${jwt.audience:fhirserver-api}")
    private String audience;

    private final TokenBlacklistService blacklistService;

    public JwtTokenProvider(TokenBlacklistService blacklistService) {
        this.blacklistService = blacklistService;
    }

    /**
     * Gera um par de tokens (access + refresh) para um usuário
     * 
     * @param username nome do usuário
     * @param roles conjunto de roles do usuário
     * @return TokenResponse contendo access_token e refresh_token
     */
    public TokenResponse generateTokenPair(String username, Set<String> roles) {
        long now = System.currentTimeMillis();
        String jti = UUID.randomUUID().toString(); // JTI único para revogação
        
        // Access token: 15 minutos (curto)
        String accessToken = buildToken(
            username,
            roles,
            jti,
            new Date(now + accessTokenExpiration),
            true // é access token
        );
        
        // Refresh token: 7 dias (longo)
        String refreshToken = buildToken(
            username,
            roles,
            UUID.randomUUID().toString(), // JTI diferente para refresh
            new Date(now + refreshTokenExpiration),
            false // é refresh token
        );
        
        return TokenResponse.builder()
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .tokenType("Bearer")
            .expiresIn(accessTokenExpiration / 1000) // em segundos
            .username(username)
            .roles(roles)
            .build();
    }

    /**
     * Renova o access token usando um refresh token válido
     * 
     * @param refreshToken token de refresh
     * @param username nome do usuário (extraído do token ou fornecido)
     * @param roles roles do usuário
     * @return novo TokenResponse com novo access_token
     */
    public TokenResponse refreshAccessToken(String refreshToken, String username, Set<String> roles) {
        if (!validateToken(refreshToken)) {
            throw new RuntimeException("Refresh token inválido ou expirado");
        }
        
        String jti = getJtiFromToken(refreshToken);
        if (jti != null && blacklistService.isTokenRevoked(jti)) {
            throw new RuntimeException("Refresh token foi revogado");
        }
        
        long now = System.currentTimeMillis();
        String newJti = UUID.randomUUID().toString();
        
        // Novo access token
        String newAccessToken = buildToken(
            username,
            roles,
            newJti,
            new Date(now + accessTokenExpiration),
            true
        );
        
        return TokenResponse.builder()
            .accessToken(newAccessToken)
            .refreshToken(refreshToken) // refresh token mantém o mesmo
            .tokenType("Bearer")
            .expiresIn(accessTokenExpiration / 1000)
            .username(username)
            .roles(roles)
            .build();
    }

    /**
     * Revoga um token (logout)
     * Adiciona o token à blacklist até sua data de expiração
     * 
     * @param token token a ser revogado
     */
    public void revokeToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            String jti = claims.get("jti", String.class);
            long expirationTime = claims.getExpiration().getTime();
            
            if (jti != null) {
                blacklistService.revokeToken(jti, expirationTime);
                log.info("Token revoked: user={}, jti={}", claims.getSubject(), jti);
            }
        } catch (Exception e) {
            log.error("Error revoking token: {}", e.getMessage());
        }
    }

    /**
     * Extrai o JTI (JWT ID) de um token
     * 
     * @param token token JWT
     * @return JTI ou null
     */
    public String getJtiFromToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return claims.get("jti", String.class);
        } catch (Exception e) {
            log.error("Error extracting JTI from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Método privado que constrói o token JWT com claims OIDC padrão
     * 
     * @param username nome do usuário
     * @param roles conjunto de roles
     * @param jti JWT ID único
     * @param expiryDate data de expiração
     * @param isAccessToken true para access token, false para refresh token
     * @return token JWT compacto
     */
    private String buildToken(String username, Set<String> roles, String jti, Date expiryDate, boolean isAccessToken) {
        long now = System.currentTimeMillis();
        Date nowDate = new Date(now);
        Date notBeforeDate = new Date(now + 1000); // Token válido 1 segundo depois (nbf)

        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());

        var tokenBuilder = Jwts.builder()
            // Claims OIDC padrão
            .setIssuer(issuer)                      // iss: quem emitiu o token
            .setAudience(audience)                  // aud: para quem é o token
            .setSubject(username)                   // sub: usuário
            .setIssuedAt(nowDate)                   // iat: quando foi emitido
            .setNotBefore(notBeforeDate)            // nbf: válido a partir de
            .setExpiration(expiryDate)              // exp: quando expira
            
            // Claims customizados
            .claim("jti", jti)                      // JWT ID único (para revogação)
            .claim("username", username)
            .claim("roles", roles)                  // Roles como List (JSON array)
            .claim("token_type", isAccessToken ? "access" : "refresh");

        return tokenBuilder
            .signWith(key, SignatureAlgorithm.HS512)
            .compact();
    }

    /**
     * Generate JWT token for a user with given username and roles.
     * This version works without a User entity - uses just username and role set.
     * 
     * @deprecated Use generateTokenPair() instead for access + refresh tokens
     */
    @Deprecated
    public String generateTokenForUser(String username, Set<String> roles) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + jwtExpiration);
        String jti = UUID.randomUUID().toString();
        
        return buildToken(username, roles, jti, expiryDate, true);
    }

    public String getUsernameFromToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return claims.getSubject();
        } catch (Exception e) {
            log.error("Error extracting username from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get roles from JWT token as a Set of role names like "ADMIN", "MEDICO"
     * Suporta tanto o novo formato (array JSON) como o antigo (string com vírgula)
     */
    public Set<String> getRolesFromToken(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            
            // Tenta novo formato (roles como List/Array)
            try {
                List<String> rolesList = claims.get("roles", List.class);
                if (rolesList != null && !rolesList.isEmpty()) {
                    return Set.copyOf(rolesList);
                }
            } catch (Exception e) {
                // Se falhar, tenta formato antigo (String com vírgula)
            }
            
            // Formato antigo: "ADMIN,MEDICO"
            String rolesStr = claims.get("roles", String.class);
            if (rolesStr == null || rolesStr.isEmpty()) {
                return Set.of();
            }
            return Set.of(rolesStr.split(","));
        } catch (Exception e) {
            log.error("Error extracting roles from token: {}", e.getMessage());
            return Set.of();
        }
    }

    public boolean validateToken(String token) {
        try {
            SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
            Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token);
            
            // Verifica se token foi revogado
            String jti = getJtiFromToken(token);
            if (jti != null && blacklistService.isTokenRevoked(jti)) {
                log.warn("Token is revoked: jti={}", jti);
                return false;
            }
            
            return true;
        } catch (io.jsonwebtoken.security.SecurityException ex) {
            log.error("Invalid JWT signature: {}", ex.getMessage());
        } catch (io.jsonwebtoken.MalformedJwtException ex) {
            log.error("Invalid JWT token: {}", ex.getMessage());
        } catch (io.jsonwebtoken.ExpiredJwtException ex) {
            log.error("Expired JWT token: {}", ex.getMessage());
        } catch (io.jsonwebtoken.UnsupportedJwtException ex) {
            log.error("Unsupported JWT token: {}", ex.getMessage());
        } catch (IllegalArgumentException ex) {
            log.error("JWT claims string is empty: {}", ex.getMessage());
        }
        return false;
    }

    public boolean isTokenExpired(String token) {
        try {
            Claims claims = getAllClaimsFromToken(token);
            return claims.getExpiration().before(new Date());
        } catch (Exception e) {
            log.error("Error checking token expiration: {}", e.getMessage());
            return true;
        }
    }

    private Claims getAllClaimsFromToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes());
        return Jwts.parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
