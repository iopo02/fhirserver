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
 * - POST /auth/login - Login with username/password, returns access + refresh
 * tokens
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
            log.info("Login attempt for user: {}", loginRequest.getEmail_address());

            // Validate credentials using UserService
            if (!userService.validateCredentials(loginRequest.getEmail_address(), loginRequest.getPassword())) {
                log.warn("Invalid credentials for user: {}", loginRequest.getEmail_address());
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(new ErrorResponse("Invalid credentials", System.currentTimeMillis()));
            }

            // Get user from database
            User user = userService.getUserByEmail(loginRequest.getEmail_address())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Generate access + refresh tokens
            TokenResponse tokenPair = jwtTokenProvider.generateTokenPair(
                    user.getEmail(),
                    user.getRoles());

            log.info("Login successful for user: {} with roles: {}", user.getEmail(), user.getRoles());

            return ResponseEntity.ok(tokenPair);

        } catch (Exception e) {
            log.error("Login failed for user: {}", loginRequest.getEmail_address(), e);
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
                    roles);

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
     * Logout - revoga o refresh token
     * 
     * POST /auth/logout
     * Body: { "refresh_token": "..." }
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestBody RefreshTokenRequest request) {
        try {
            if (request.getRefreshToken() == null || request.getRefreshToken().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Refresh token required", System.currentTimeMillis()));
            }

            String username = jwtTokenProvider.getUsernameFromToken(request.getRefreshToken());

            // Revoga o refresh token
            jwtTokenProvider.revokeToken(request.getRefreshToken());

            log.info("Logout successful for user: {}", username);

            return ResponseEntity.ok(Map.of(
                    "message", "Logged out successfully",
                    "timestamp", System.currentTimeMillis()));
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

            String email = authentication.getName();
            User user = userService.getUserByEmail(email)
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

            log.debug("User info retrieved for: {}", user);
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

        private String email;

        private String password;

        public LoginRequest() {
        }

        public LoginRequest(String email, String password) {
            this.email = email;
            this.password = password;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        // Getters e Setters alternativos em Snake Case para garantir compatibilidade
        // total
        public String getEmail_address() {
            return email;
        }

        public void setEmail_address(String email) {
            this.email = email;
        }

        public String getPass_word() {
            return password;
        }

        public void setPass_word(String password) {
            this.password = password;
        }
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

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    /**
     * DTO for refresh token requests
     */
    public static class RefreshTokenRequest {
        private String refresh_token;

        public RefreshTokenRequest() {
        }

        public RefreshTokenRequest(String refreshToken) {
            this.refresh_token = refreshToken;
        }

        public String getRefreshToken() {
            return refresh_token;
        }

        public void setRefreshToken(String refreshToken) {
            this.refresh_token = refreshToken;
        }

        // Getter alternativo com snake_case para JSON
        public String getRefresh_token() {
            return refresh_token;
        }

        public void setRefresh_token(String refresh_token) {
            this.refresh_token = refresh_token;
        }
    }
}
