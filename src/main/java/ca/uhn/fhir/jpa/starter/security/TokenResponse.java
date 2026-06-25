package ca.uhn.fhir.jpa.starter.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Set;

/**
 * Response DTO para login/refresh token
 * Contém access token (curto) e refresh token (longo)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Access token (JWT curto, ~15-30 min)
     * Usado em Authorization: Bearer <access_token>
     */
    @JsonProperty("access_token")
    private String accessToken;

    /**
     * Refresh token (JWT longo, ~7 dias)
     * Armazenado seguro no cliente e usado para renovar access token
     */
    @JsonProperty("refresh_token")
    private String refreshToken;

    /**
     * Tipo de token (sempre "Bearer" para JWT)
     */
    @JsonProperty("token_type")
    private String tokenType;

    /**
     * Tempo de expiração do access token em segundos (para o cliente saber quando renovar)
     */
    @JsonProperty("expires_in")
    private Long expiresIn;

    /**
     * Roles do usuário (informativo, também está no JWT)
     */
    private Set<String> roles;

    /**
     * Username do usuário autenticado
     */
    private String username;
}
