package ca.uhn.fhir.jpa.starter.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Minimal security configuration for JWT-based authentication.
 * 
 * Spring Security full autoconfiguration is excluded to avoid version conflicts.
 * JWT authentication is handled by JwtAuthenticationFilter registered as a servlet filter.
 * 
 * Test Credentials:
 * - admin / admin123 (role: ADMIN)
 * - doctor / doctor123 (role: MEDICO)
 * 
 * This is a simplified, academic implementation. 
 * In production, use an external Identity Provider (Keycloak, OAuth2/OIDC, SMART on FHIR).
 */
@Configuration
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider, JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterBean() {
        FilterRegistrationBean<JwtAuthenticationFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(jwtAuthenticationFilter);
        registrationBean.addUrlPatterns("/fhir/*", "/auth/*", "/actuator/*", "/*");
        registrationBean.setOrder(1);
        return registrationBean;
    }
}
