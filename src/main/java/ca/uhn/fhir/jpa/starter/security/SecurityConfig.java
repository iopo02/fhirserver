package ca.uhn.fhir.jpa.starter.security;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Minimal security configuration for JWT-based authentication.
 * 
 * - JWT filter é registado manualmente para proteger /fhir/*, /auth/*,
 * /actuator/*
 * - @EnableMethodSecurity permite usar @PreAuthorize para RBAC granular
 * - Recursos estáticos não são filtrados (vêem o filtro mas a função
 * shouldNotFilter exclui-os)
 * 
 * Test Credentials:
 * - admin / admin123 (role: ADMIN)
 * - doctor / doctor123 (role: MEDICO)
 * 
 * RBAC Rules:
 * - GET /fhir/* : ADMIN e MEDICO
 * - POST /fhir/* : ADMIN e MEDICO
 * - DELETE /fhir/* : ADMIN apenas
 * - PUT /fhir/* : ADMIN e MEDICO
 * 
 * This is a simplified, academic implementation.
 * In production, use an external Identity Provider (Keycloak, OAuth2/OIDC,
 * SMART on FHIR).
 */
@Configuration
@EnableMethodSecurity
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

    /**
     * Registar JWT filter apenas em endpoints protegidos
     * O filtro tem shouldNotFilter() para excluir recursos estáticos e login
     * público
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {

        http
                .csrf(csrf -> csrf.disable())

                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth

                        // Public endpoints - no authentication required
                        .requestMatchers(
                                new AntPathRequestMatcher("/fhir/metadata"),
                                new AntPathRequestMatcher("/auth/login"),
                                new AntPathRequestMatcher("/auth/refresh"),
                                new AntPathRequestMatcher("/actuator/health/**"),
                                new AntPathRequestMatcher("/fhir/swagger-ui/**"),
                                new AntPathRequestMatcher("/fhir/api-docs/**"))
                        .permitAll()

                        // Protected FHIR endpoints - require ADMIN or MEDICO role
                        .requestMatchers(new AntPathRequestMatcher("/fhir/**"))
                        .hasAnyRole("ADMIN", "MEDICO")

                        // Protected auth endpoints - require authentication
                        .requestMatchers(
                                new AntPathRequestMatcher("/auth/me"),
                                new AntPathRequestMatcher("/auth/logout"))
                        .authenticated()

                        // All other requests require authentication
                        .anyRequest()
                        .authenticated())

                .httpBasic(httpBasic -> httpBasic.disable())

                .formLogin(form -> form.disable())

                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
