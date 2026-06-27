package ca.uhn.fhir.jpa.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Authentication Controller - Simplified for demonstration
 * 
 * Provides endpoints for:
 * - POST /auth/login - Login with username/password, returns access + refresh tokens
 * - POST /auth/refresh - Renew access token using refresh token
 * - POST /auth/logout - Revoke access token (logout)
 * - GET /auth/me - Get current user info
 * 
 * Uses UserService for user validation and management.
 * In production, use an external Identity Provider (Keycloak, OAuth2/OIDC).
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserService userService;

    public AuthController(JwtTokenProvider jwtTokenProvider, UserService userService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userService = userService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest loginRequest) {
        try {
            log.info("Login attempt for user: {}", loginRequest.getUsername());

            // Validate credentials using UserService
            if (!userService.validateCredentials(loginRequest.getUsername(), loginRequest.getPassword())) {
                log.warn("Invalid credentials for user: {}", loginRequest.getUsername());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Invalid credentials", System.currentTimeMillis()));
            }

            // Get user from database
            User user = userService.getUserByUsername(loginRequest.getUsername())
                .orElseThrow(() -> new RuntimeException("User not found"));

            // Generate access + refresh tokens
            TokenResponse tokenPair = jwtTokenProvider.generateTokenPair(
                user.getUsername(),
                user.getRoles()
            );

            log.info("Login successful for user: {} with roles: {}", user.getUsername(), user.getRoles());

            return ResponseEntity.ok(tokenPair);

        } catch (Exception e) {
            log.error("Login failed for user: {}", loginRequest.getUsername(), e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("Invalid credentials", System.currentTimeMillis()));
        }
    }

    /**
     * Renova o access token usando um refresh token válido
     * 
     * POST /auth/refresh
     * Body: { "refresh_token": "..." }
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        try {
            if (request.getRefreshToken() == null || request.getRefreshToken().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Refresh token required", System.currentTimeMillis()));
            }

            log.info("Refresh token request");

            // Extrai dados do token
            String username = jwtTokenProvider.getUsernameFromToken(request.getRefreshToken());
            Set<String> roles = jwtTokenProvider.getRolesFromToken(request.getRefreshToken());

            if (username == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("Invalid refresh token", System.currentTimeMillis()));
            }

            // Renova o access token
            TokenResponse newTokens = jwtTokenProvider.refreshAccessToken(
                request.getRefreshToken(),
                username,
                roles
            );

            log.info("Token refreshed for user: {}", username);
            return ResponseEntity.ok(newTokens);

        } catch (RuntimeException e) {
            log.error("Token refresh failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(e.getMessage(), System.currentTimeMillis()));
        } catch (Exception e) {
            log.error("Token refresh error", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Internal server error", System.currentTimeMillis()));
        }
    }

    /**
     * Logout - revoga o access token
     * 
     * POST /auth/logout
     * Header: Authorization: Bearer <access_token>
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Authorization header required", System.currentTimeMillis()));
            }

            String token = authHeader.substring(7);
            String username = jwtTokenProvider.getUsernameFromToken(token);

            // Revoga o token
            jwtTokenProvider.revokeToken(token);

            log.info("Logout successful for user: {}", username);

            return ResponseEntity.ok(Map.of(
                "message", "Logged out successfully",
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Logout failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Logout failed", System.currentTimeMillis()));
        }
    }

    /**
     * Get current user info - requires valid JWT token
     * 
     * GET /auth/me
     * Header: Authorization: Bearer <access_token>
     * 
     * Returns user data (id, username, email, roles)
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication == null || !authentication.isAuthenticated()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("No authenticated user", System.currentTimeMillis()));
            }

            String username = authentication.getName();
            User user = userService.getUserByUsername(username)
                .orElseThrow(() -> new RuntimeException("User not found"));

            // Build user response
            Map<String, Object> userResponse = new HashMap<>();
            userResponse.put("id", user.getId());
            userResponse.put("username", user.getUsername());
            userResponse.put("email", user.getEmail());
            userResponse.put("firstName", user.getFirstName());
            userResponse.put("lastName", user.getLastName());
            userResponse.put("roles", user.getRoles());
            userResponse.put("active", user.getActive());
            userResponse.put("locked", user.getLocked());
            userResponse.put("timestamp", System.currentTimeMillis());

            log.debug("User info retrieved for: {}", username);
            return ResponseEntity.ok(userResponse);

        } catch (Exception e) {
            log.error("Error retrieving user info", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Error retrieving user info", System.currentTimeMillis()));
        }
    }

    /**
     * DTO for login requests
     */
    public static class LoginRequest {
        private String username;
        private String password;

        public LoginRequest() {}
        public LoginRequest(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * DTO for error responses
     */
    public static class ErrorResponse {
        private String error;
        private long timestamp;

        public ErrorResponse(String error, long timestamp) {
            this.error = error;
            this.timestamp = timestamp;
        }

        public String getError() { return error; }
        public void setError(String error) { this.error = error; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }

    /**
     * DTO for refresh token requests
     */
    public static class RefreshTokenRequest {
        private String refresh_token;

        public RefreshTokenRequest() {}
        public RefreshTokenRequest(String refreshToken) {
            this.refresh_token = refreshToken;
        }

        public String getRefreshToken() { return refresh_token; }
        public void setRefreshToken(String refreshToken) { this.refresh_token = refreshToken; }

        // Getter alternativo com snake_case para JSON
        public String getRefresh_token() { return refresh_token; }
        public void setRefresh_token(String refresh_token) { this.refresh_token = refresh_token; }
    }
}
