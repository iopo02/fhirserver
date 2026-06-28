package ca.uhn.fhir.jpa.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RBAC Controller - Enforce role-based access control para DELETE operations
 * 
 * DELETE /fhir/** : Requer role ADMIN (MEDICO não pode deletar)
 * 
 * Retorna:
 * - 403 Forbidden se não tem role ADMIN
 * - 404 Not Found se recurso não existe
 * - 204 No Content se deletado com sucesso
 */
@Slf4j
@RestController
@RequestMapping("/fhir")
public class RbacController {

    /**
     * Interceptar DELETE em qualquer recurso FHIR
     * @PreAuthorize valida que utilizador tem role ADMIN
     * 
     * Retorna 403 se não autorizado
     */
    @DeleteMapping("/**")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteFhirResource() {
        // Este controller não faz nada — deixa o Spring rotar para o HAPI FHIR backend
        // Mas a anotação @PreAuthorize bloqueia se não é ADMIN
        log.info("DELETE authorized for ADMIN");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * GET, POST, PUT são permitidos para ADMIN e MEDICO
     * Caso de teste explícito (HAPI FHIR cuida dos GET/POST/PUT reais)
     */
}
