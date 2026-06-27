package ca.uhn.fhir.jpa.starter.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * User Repository - provides database access for User entity
 * 
 * Supports:
 * - CRUD operations
 * - Search by username, email
 * - Filtering by role and account status
 * - Pagination
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    
    /**
     * Find user by username
     */
    Optional<User> findByUsername(String username);
    
    /**
     * Find user by email
     */
    Optional<User> findByEmail(String email);
    
    /**
     * Search users by username or email with filtering
     * 
     * @param searchTerm username or email to search for
     * @param active filter by active status
     * @param locked filter by locked status
     * @param pageable pagination info
     * @return page of users matching criteria
     */
    @Query("""
        SELECT u FROM User u 
        WHERE (LOWER(u.username) LIKE LOWER(CONCAT('%', :searchTerm, '%')) 
           OR LOWER(u.email) LIKE LOWER(CONCAT('%', :searchTerm, '%')))
        AND (:active IS NULL OR u.active = :active)
        AND (:locked IS NULL OR u.locked = :locked)
        ORDER BY u.createdAt DESC
    """)
    Page<User> searchUsers(
        @Param("searchTerm") String searchTerm,
        @Param("active") Boolean active,
        @Param("locked") Boolean locked,
        Pageable pageable
    );
    
    /**
     * Find all users with role filtering
     */
    @Query("""
        SELECT u FROM User u 
        WHERE (:active IS NULL OR u.active = :active)
        AND (:locked IS NULL OR u.locked = :locked)
        AND (:role IS NULL OR :role MEMBER OF u.roles)
        ORDER BY u.createdAt DESC
    """)
    Page<User> findAll(
        @Param("active") Boolean active,
        @Param("locked") Boolean locked,
        @Param("role") String role,
        Pageable pageable
    );
    
    /**
     * Find users by role
     */
    @Query("SELECT u FROM User u WHERE :role MEMBER OF u.roles")
    List<User> findByRole(@Param("role") String role);
    
    /**
     * Check if username exists
     */
    boolean existsByUsername(String username);
    
    /**
     * Check if email exists
     */
    boolean existsByEmail(String email);
}
