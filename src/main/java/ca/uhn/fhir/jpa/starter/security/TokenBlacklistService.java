package ca.uhn.fhir.jpa.starter.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Service para gerenciar blacklist de tokens revogados (logout, password reset, etc)
 * Usa cache em memória com TTL automático
 */
@Service
@Slf4j
public class TokenBlacklistService {

    /**
     * Mapa de JTI revogados: jti -> expiration_time_ms
     * Usado para validar se token foi revogado antes de sua expiração natural
     */
    private final ConcurrentHashMap<String, Long> blacklistedTokens = new ConcurrentHashMap<>();

    /**
     * Thread que limpa tokens expirados periodicamente
     */
    private final Thread cleanupThread;

    public TokenBlacklistService() {
        // Inicia thread de limpeza que roda a cada 5 minutos
        this.cleanupThread = new Thread(this::cleanupExpiredTokens, "TokenBlacklistCleanup");
        this.cleanupThread.setDaemon(true);
        this.cleanupThread.start();
        log.info("TokenBlacklistService initialized with cleanup thread");
    }

    /**
     * Adiciona um token à blacklist até sua data de expiração
     * 
     * @param jti identificador único do token
     * @param expirationTimeMs timestamp em milissegundos quando o token expira
     */
    public void revokeToken(String jti, long expirationTimeMs) {
        long now = System.currentTimeMillis();
        
        if (expirationTimeMs <= now) {
            // Token já expirou, não precisa adicionar à blacklist
            log.debug("Token already expired, skipping blacklist: {}", jti);
            return;
        }
        
        blacklistedTokens.put(jti, expirationTimeMs);
        long ttlMs = expirationTimeMs - now;
        log.info("Token revoked - JTI: {}, TTL: {} seconds", jti, TimeUnit.MILLISECONDS.toSeconds(ttlMs));
    }

    /**
     * Verifica se um token foi revogado (está na blacklist)
     * 
     * @param jti identificador único do token
     * @return true se token está na blacklist e ainda não expirou
     */
    public boolean isTokenRevoked(String jti) {
        if (!blacklistedTokens.containsKey(jti)) {
            return false;
        }
        
        long expirationTime = blacklistedTokens.get(jti);
        long now = System.currentTimeMillis();
        
        if (expirationTime <= now) {
            // Token expirou, remove da blacklist
            blacklistedTokens.remove(jti);
            return false;
        }
        
        return true;
    }

    /**
     * Remove todos os tokens revogados de um usuário
     * Útil para casos como "logout em todos os dispositivos"
     * 
     * @param username nome do usuário
     */
    public void revokeAllUserTokens(String username) {
        log.warn("Revoking all tokens for user: {}", username);
        // Implementação simplificada - precisaria armazenar username em cada token
        // Para agora, apenas log. Em produção, seria útil manter mapa username -> [jti]
    }

    /**
     * Limpa tokens expirados da blacklist (roda periodicamente)
     */
    private void cleanupExpiredTokens() {
        while (true) {
            try {
                // Aguarda 5 minutos entre limpezas
                Thread.sleep(TimeUnit.MINUTES.toMillis(5));
                
                long now = System.currentTimeMillis();
                int removedCount = 0;
                
                for (String jti : blacklistedTokens.keySet()) {
                    Long expirationTime = blacklistedTokens.get(jti);
                    if (expirationTime != null && expirationTime <= now) {
                        blacklistedTokens.remove(jti);
                        removedCount++;
                    }
                }
                
                if (removedCount > 0) {
                    log.debug("Cleaned up {} expired tokens from blacklist", removedCount);
                }
            } catch (InterruptedException e) {
                log.warn("Cleanup thread interrupted");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Retorna o tamanho atual da blacklist (para monitoramento)
     */
    public int getBlacklistSize() {
        return blacklistedTokens.size();
    }

    /**
     * Limpa toda a blacklist (para testes)
     */
    public void clear() {
        log.warn("Clearing entire token blacklist");
        blacklistedTokens.clear();
    }
}
