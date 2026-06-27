package ca.uhn.fhir.jpa.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * User Admin Controller - endpoints for admin user management
 * 
 * All endpoints require ADMIN role
 * 
 * Endpoints:
 * - POST /admin/users - Create new user
 * - GET /admin/users - List all users with filtering
 * - GET /admin/users/{id} - Get user by ID
 * - POST /admin/users/{id}/lock - Lock user account
 * - POST /admin/users/{id}/unlock - Unlock user account
 * - PUT /admin/users/{id}/roles - Update user roles
 * - DELETE /admin/users/{id} - Deactivate user account
 */
@Slf4j
@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserAdminController {
    
    private final UserService userService;
    
    public UserAdminController(UserService userService) {
        this.userService = userService;
    }
    
    /**
     * Get currently authenticated admin username
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "SYSTEM";
    }
    
    /**
     * Create a new user account
     * 
     * POST /admin/users
     * 
     * Request body:
     * {
     *   "username": "doctor1",
     *   "email": "doctor1@example.com",
     *   "password": "password123",
     *   "firstName": "João",
     *   "lastName": "Silva",
     *   "roles": ["MEDICO"]
     * }
     * 
     * Response: 201 Created with user details
     */
    @PostMapping
    public ResponseEntity<?> createUser(@RequestBody CreateUserRequest request) {
        try {
            String admin = getCurrentUsername();
            User user = userService.createUser(request, admin);
            
            return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(new ApiResponse(
                    true,
                    "User created successfully",
                    UserResponse.fromUser(user)
                ));
        } catch (IllegalArgumentException e) {
            log.warn("User creation failed: {}", e.getMessage());
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(false, e.getMessage(), null));
        } catch (Exception e) {
            log.error("User creation error", e);
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error creating user", null));
        }
    }
    
    /**
     * List all users with optional filtering and pagination
     * 
     * GET /admin/users?search=username&role=MEDICO&active=true&locked=false&page=0&size=10
     * 
     * Query parameters:
     * - search: search by username or email (optional)
     * - role: filter by role - ADMIN or MEDICO (optional)
     * - active: filter by active status - true or false (optional)
     * - locked: filter by locked status - true or false (optional)
     * - page: page number (default 0)
     * - size: page size (default 20)
     * 
     * Response: Page of users matching criteria
     */
    @GetMapping
    public ResponseEntity<?> listUsers(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String role,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) Boolean locked,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        try {
            Pageable pageable = PageRequest.of(page, Math.min(size, 100));
            Page<User> users = userService.listUsers(search, active, locked, role, pageable);
            
            List<UserResponse> userResponses = users.getContent()
                .stream()
                .map(UserResponse::fromUser)
                .collect(Collectors.toList());
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Users retrieved successfully");
            response.put("data", userResponses);
            response.put("pagination", Map.of(
                "currentPage", users.getNumber(),
                "pageSize", users.getSize(),
                "totalElements", users.getTotalElements(),
                "totalPages", users.getTotalPages(),
                "hasNext", users.hasNext(),
                "hasPrevious", users.hasPrevious()
            ));
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error listing users", e);
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error retrieving users", null));
        }
    }
    
    /**
     * Get user by ID
     * 
     * GET /admin/users/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getUserById(@PathVariable Long id) {
        try {
            User user = userService.getUserById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
            
            return ResponseEntity.ok(new ApiResponse(
                true,
                "User retrieved successfully",
                UserResponse.fromUser(user)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse(false, e.getMessage(), null));
        }
    }
    
    /**
     * Lock a user account
     * 
     * POST /admin/users/{id}/lock
     * 
     * Request body (optional):
     * {
     *   "reason": "Suspicious activity"
     * }
     * 
     * Response: Updated user with locked=true
     */
    @PostMapping("/{id}/lock")
    public ResponseEntity<?> lockUser(
        @PathVariable Long id,
        @RequestBody(required = false) Map<String, String> body
    ) {
        try {
            String admin = getCurrentUsername();
            User user = userService.lockUser(id, admin);
            
            return ResponseEntity.ok(new ApiResponse(
                true,
                "User account locked successfully",
                UserResponse.fromUser(user)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse(false, e.getMessage(), null));
        }
    }
    
    /**
     * Unlock a user account
     * 
     * POST /admin/users/{id}/unlock
     * 
     * Response: Updated user with locked=false
     */
    @PostMapping("/{id}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable Long id) {
        try {
            String admin = getCurrentUsername();
            User user = userService.unlockUser(id, admin);
            
            return ResponseEntity.ok(new ApiResponse(
                true,
                "User account unlocked successfully",
                UserResponse.fromUser(user)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse(false, e.getMessage(), null));
        }
    }
    
    /**
     * Update user roles
     * 
     * PUT /admin/users/{id}/roles
     * 
     * Request body:
     * {
     *   "roles": ["ADMIN", "MEDICO"]
     * }
     * 
     * Response: Updated user with new roles
     */
    @PutMapping("/{id}/roles")
    public ResponseEntity<?> updateUserRoles(
        @PathVariable Long id,
        @RequestBody UpdateUserRolesRequest request
    ) {
        try {
            if (request.getRoles() == null || request.getRoles().isEmpty()) {
                return ResponseEntity
                    .status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "At least one role must be assigned", null));
            }
            
            String admin = getCurrentUsername();
            User user = userService.updateUserRoles(id, request.getRoles(), admin);
            
            return ResponseEntity.ok(new ApiResponse(
                true,
                "User roles updated successfully",
                UserResponse.fromUser(user)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse(false, e.getMessage(), null));
        }
    }
    
    /**
     * Deactivate user account
     * 
     * DELETE /admin/users/{id}
     * 
     * Response: Updated user with active=false
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deactivateUser(@PathVariable Long id) {
        try {
            String admin = getCurrentUsername();
            User user = userService.deactivateUser(id, admin);
            
            return ResponseEntity.ok(new ApiResponse(
                true,
                "User account deactivated successfully",
                UserResponse.fromUser(user)
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse(false, e.getMessage(), null));
        }
    }
    
    /**
     * Generic API response wrapper
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class ApiResponse {
        private boolean success;
        private String message;
        private Object data;
    }
}
