package ca.uhn.fhir.jpa.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * User Service - business logic for user account management
 * 
 * Responsibilities:
 * - Create new user accounts (admin only)
 * - List and search users with filtering
 * - Lock/unlock user accounts
 * - Validate user credentials
 * - Manage user roles
 */
@Slf4j
@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create a new user account
     * 
     * @param request   create user request with username, email, password, roles
     * @param createdBy username of admin creating this user
     * @return created user
     * @throws IllegalArgumentException if username/email already exists
     */
    public User createUser(CreateUserRequest request, String createdBy) {
        // Validate input
        if (request.getUsername() == null || request.getUsername().isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email cannot be empty");
        }
        if (request.getPassword() == null || request.getPassword().isBlank()) {
            throw new IllegalArgumentException("Password cannot be empty");
        }
        if (request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters");
        }

        // Check if username already exists
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username '" + request.getUsername() + "' already exists");
        }

        // Check if email already exists
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email '" + request.getEmail() + "' already exists");
        }

        // Validate roles - only ADMIN and MEDICO allowed
        Set<String> roles = new HashSet<>(request.getRoles());
        roles.forEach(role -> {
            if (!role.equals("ADMIN") && !role.equals("MEDICO")) {
                throw new IllegalArgumentException("Invalid role: " + role + ". Only ADMIN and MEDICO are allowed");
            }
        });

        if (roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role must be assigned");
        }

        // Create user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .roles(roles)
                .active(true)
                .locked(false)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .createdBy(createdBy)
                .updatedBy(createdBy)
                .build();

        User savedUser = userRepository.save(user);
        log.info("User '{}' created by '{}' with roles: {}", savedUser.getUsername(), createdBy, roles);
        return savedUser;
    }

    /**
     * Get user by username
     */
    public Optional<User> getUserByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Get user by email
     */
    public Optional<User> getUserByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Get user by ID
     */
    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    /**
     * List all users with optional filtering
     * 
     * @param searchTerm optional search term for username/email
     * @param active     optional filter by active status
     * @param locked     optional filter by locked status
     * @param role       optional filter by role
     * @param pageable   pagination info
     * @return page of users
     */
    public Page<User> listUsers(String searchTerm, Boolean active, Boolean locked, String role, Pageable pageable) {
        // If search term provided, use search query
        if (searchTerm != null && !searchTerm.isBlank()) {
            return userRepository.searchUsers(searchTerm, active, locked, pageable);
        }

        // Otherwise use regular find all
        return userRepository.findByFilters(active, locked, role, pageable);
    }

    /**
     * Lock a user account
     * 
     * @param userId   ID of user to lock
     * @param lockedBy username of admin performing action
     * @return updated user
     * @throws IllegalArgumentException if user not found
     */
    public User lockUser(Long userId, String lockedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        user.setLocked(true);
        user.setUpdatedAt(LocalDateTime.now());
        user.setUpdatedBy(lockedBy);
        User updated = userRepository.save(user);
        log.info("User '{}' locked by '{}'", user.getUsername(), lockedBy);
        return updated;
    }

    /**
     * Unlock a user account
     * 
     * @param userId     ID of user to unlock
     * @param unlockedBy username of admin performing action
     * @return updated user
     * @throws IllegalArgumentException if user not found
     */
    public User unlockUser(Long userId, String unlockedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        user.setLocked(false);
        user.setUpdatedAt(LocalDateTime.now());
        user.setUpdatedBy(unlockedBy);
        User updated = userRepository.save(user);
        log.info("User '{}' unlocked by '{}'", user.getUsername(), unlockedBy);
        return updated;
    }

    /**
     * Deactivate a user account
     * 
     * @param userId        ID of user to deactivate
     * @param deactivatedBy username of admin performing action
     * @return updated user
     */
    public User deactivateUser(Long userId, String deactivatedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        user.setActive(false);
        user.setUpdatedAt(LocalDateTime.now());
        user.setUpdatedBy(deactivatedBy);
        User updated = userRepository.save(user);
        log.info("User '{}' deactivated by '{}'", user.getUsername(), deactivatedBy);
        return updated;
    }

    /**
     * Activate a user account
     * 
     * @param userId      ID of user to activate
     * @param activatedBy username of admin performing action
     * @return updated user
     */
    public User activateUser(Long userId, String activatedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        user.setActive(true);
        user.setUpdatedAt(LocalDateTime.now());
        user.setUpdatedBy(activatedBy);
        User updated = userRepository.save(user);
        log.info("User '{}' activated by '{}'", user.getUsername(), activatedBy);
        return updated;
    }

    /**
     * Validate user credentials
     * 
     * @param username username
     * @param password plain text password
     * @return true if credentials are valid and user is active/unlocked
     */
    public boolean validateCredentials(String email, String password) {
        log.info("Tentativa de login para o utilizador: {}", email);

        Optional<User> user = userRepository.findByEmail(email); // ← Muda para email
        if (user.isEmpty()) {
            return false;
        }

        User u = user.get();

        if (!u.getActive() || u.getLocked()) {
            log.warn("Utilizador bloqueado ou inativo: {}", email);
            return false;
        }

        return passwordEncoder.matches(password.trim(), u.getPassword());
    }

    /**
     * Update user roles
     * 
     * @param userId    ID of user to update
     * @param roles     new set of roles
     * @param updatedBy username of admin performing action
     * @return updated user
     */
    public User updateUserRoles(Long userId, Set<String> roles, String updatedBy) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        // Validate roles
        roles.forEach(role -> {
            if (!role.equals("ADMIN") && !role.equals("MEDICO")) {
                throw new IllegalArgumentException("Invalid role: " + role);
            }
        });

        user.setRoles(roles);
        User updated = userRepository.save(user);
        log.info("User '{}' roles updated to: {} by '{}'", user.getUsername(), roles, updatedBy);
        return updated;
    }

    /**
     * Check if user can perform admin actions
     * 
     * @param username username to check
     * @return true if user has ADMIN role and is active/unlocked
     */
    public boolean isAdmin(String username) {
        Optional<User> user = userRepository.findByUsername(username);
        return user.isPresent() &&
                user.get().getRoles().contains("ADMIN") &&
                user.get().getActive() &&
                !user.get().getLocked();
    }
}
