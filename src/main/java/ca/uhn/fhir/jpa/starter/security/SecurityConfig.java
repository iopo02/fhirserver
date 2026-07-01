package ca.uhn.fhir.jpa.starter.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
//import org.springframework.security.web.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
            // 1. ATIVAR CORS COM CONFIGURAÇÃO EXPLÍCITA
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            
            // 2. DESATIVAR CSRF (Necessário para APIs com tokens JWT)
            .csrf(csrf -> csrf.disable())

            // 3. GERIR ACESSO AOS ENDPOINTS
            .authorizeHttpRequests(auth -> auth
                    // A. Permite que qualquer browser faça a validação CORS (Pre-flight) sem pedir token JWT
                    .requestMatchers(new AntPathRequestMatcher("/**", org.springframework.http.HttpMethod.OPTIONS.name())).permitAll()

                    // B. ROTAS TOTALMENTE PÚBLICAS
                    .requestMatchers(
                            new AntPathRequestMatcher("/**/auth/login"),
                            new AntPathRequestMatcher("/**/auth/refresh"),
                            new AntPathRequestMatcher("/dashboard"),
                            new AntPathRequestMatcher("/v3/api-docs/**"),   
                            new AntPathRequestMatcher("/**/api-docs/**"),   
                            new AntPathRequestMatcher("/**/swagger-ui/**"),
                            new AntPathRequestMatcher("/**/metadata/**"), 
                            new AntPathRequestMatcher("/**/swagger-ui.html")
                    ).permitAll()

                    // C. ROTAS QUE EXIGEM AUTENTICAÇÃO
                    .requestMatchers(
                            new AntPathRequestMatcher("*/auth/me"),
                           // new AntPathRequestMatcher("/fhir/**"),
                            new AntPathRequestMatcher("*/auth/logout")
                    ).authenticated()

                    // D. QUALQUER OUTRO PEDIDO EXIGE AUTENTICAÇÃO
                    .anyRequest().authenticated()
            )

            // 4. CONFIGURAR SESSÃO COMO STATELESS (Não guarda estado no servidor)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            // 5. GERIR EXCEÇÕES DE AUTENTICAÇÃO
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint((request, response, authException) -> {
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write("{\"error\": \"Unauthorized - Token missing or invalid\"}");
                    })
            )

            // 6. ADICIONAR O FILTRO JWT ANTES DO FILTRO PADRÃO
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
}

    // 3. FONTE DE CONFIGURAÇÃO DO CORS
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("*")); // Em produção, define a URL exata do teu Frontend
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration
                .setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "Accept", "Origin"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false); // Deve ser false se usares "*" em AllowedOrigins

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}