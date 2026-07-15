package ca.uhn.fhir.jpa.starter.security;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Query("""
        SELECT u FROM User u
        WHERE
            (:active IS NULL OR u.active = :active)
            AND (:locked IS NULL OR u.locked = :locked)
            AND (
                :search IS NULL OR
                LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
            )
        """)
    Page<User> searchUsers(
        @Param("search") String search,
        @Param("active") Boolean active,
        @Param("locked") Boolean locked,
        Pageable pageable
    );

    @Query("""
        SELECT u FROM User u
        WHERE
            (:active IS NULL OR u.active = :active)
            AND (:locked IS NULL OR u.locked = :locked)
            AND (:role IS NULL OR :role MEMBER OF u.roles)
        """)
    Page<User> findByFilters(
        @Param("active") Boolean active,
        @Param("locked") Boolean locked,
        @Param("role") String role,
        Pageable pageable
    );
}