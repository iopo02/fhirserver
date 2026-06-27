package ca.uhn.fhir.jpa.starter.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * DTO for updating user roles request
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateUserRolesRequest {
    private Set<String> roles;
}
