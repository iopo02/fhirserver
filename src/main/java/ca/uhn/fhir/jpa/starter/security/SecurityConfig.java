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
                .csrf(csrf -> csrf.disable())

                // 2. CONFIGURAR SESSÃO COMO STATELESS
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // CONFIGURAÇÃO ADICIONADA: Tratamento de Exceções para devolver JSON em vez de
                // HTML
                .exceptionHandling(exception -> exception
                        // Quando o utilizador está autenticado mas NÃO tem permissão (Ex: Médico a
                        // tentar aceder a rotas Admin) -> 403 Forbidden
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    "{\"success\":false,\"status\":403,\"error\":\"Proibido\",\"message\":\"Não tens permissões para aceder a este recurso.\"}");
                        })
                        // Quando o utilizador nem sequer está autenticado (Token inválido/ausente nas
                        // rotas protegidas) -> 401 Unauthorized
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json");
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(
                                    "{\"success\":false,\"status\":401,\"error\":\"Não Autorizado\",\"message\":\"Autenticação necessária para aceder a este recurso.\"}");
                        }))
                // 3. CONFIGURAR AUTORIZAÇÃO DE REQUISIÇÕES
                .authorizeHttpRequests(auth -> auth
                        // CORREÇÃO CRÍTICA: Usar wildcards (/**) para permitir o login sob qualquer
                        // prefixo do HAPI FHIR
                        .requestMatchers(
                                new AntPathRequestMatcher("/auth/login"),
                                new AntPathRequestMatcher("/auth/refresh"),
                                new AntPathRequestMatcher("/fhir/metadata"),
                                new AntPathRequestMatcher("/swagger-ui/**"),
                                new AntPathRequestMatcher("/v3/api-docs/**"),
                                new AntPathRequestMatcher("/actuator/health/**"))
                        .permitAll()

                        // Regras de RBAC para os endpoints FHIR e Admin
                        .requestMatchers(new AntPathRequestMatcher("/admin/**")).hasRole("ADMIN")
                        .requestMatchers(new AntPathRequestMatcher("/fhir/**")).hasAnyRole("ADMIN", "MEDICO")

                        // Endpoints que exigem autenticação explícita
                        .requestMatchers(
                                new AntPathRequestMatcher("/**/auth/me"),
                                new AntPathRequestMatcher("/**/auth/logout"))
                        .authenticated()

                        .anyRequest().authenticated())

                // 4. DESATIVAR AUTENTICAÇÃO PADRÃO DO SPRING (Basic e Form)
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())

                // 5. ADICIONAR O FILTRO JWT ANTES DO FILTRO PADRÃO
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