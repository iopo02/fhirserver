package ca.uhn.fhir.jpa.starter.security;

import org.springframework.beans.factory.annotation.Value;
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
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080,http://localhost:3000}")
    private String allowedOrigins;

    @Value("${app.cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS,PATCH}")
    private String allowedMethods;

    @Value("${app.cors.max-age:300}")
    private long maxAge;

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
                            new AntPathRequestMatcher("/"),
                            new AntPathRequestMatcher("/index.html"),
                            new AntPathRequestMatcher("/web/**"),
                            new AntPathRequestMatcher("/css/**"),
                            new AntPathRequestMatcher("/js/**"),
                            new AntPathRequestMatcher("/images/**"),
                            new AntPathRequestMatcher("/fonts/**"),
                            new AntPathRequestMatcher("/static/**"),
                            new AntPathRequestMatcher("/public/**"),
                            new AntPathRequestMatcher("/webjars/**"),
                            new AntPathRequestMatcher("/resources/**"),
                            new AntPathRequestMatcher("/**/auth/login"),
                            new AntPathRequestMatcher("/**/auth/refresh"),
                            new AntPathRequestMatcher("/dashboard"),
                            new AntPathRequestMatcher("/v3/api-docs/**"),   
                            new AntPathRequestMatcher("/**/api-docs/**"),   
                            new AntPathRequestMatcher("/**/swagger-ui/**"),
                            new AntPathRequestMatcher("/**/swagger-ui"),    // sem trailing slash
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
        
        // Parse allowed origins from config (comma-separated) and trim spaces
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim)
            .toList();
        configuration.setAllowedOrigins(origins);
        
        // Parse allowed methods from config (comma-separated)
        List<String> methods = Arrays.asList(allowedMethods.split(","));
        configuration.setAllowedMethods(methods);
        
        configuration.setAllowedHeaders(List.of(
            "Authorization", 
            "Content-Type", 
            "X-Requested-With", 
            "Accept", 
            "Origin",
            "Access-Control-Request-Method",
            "Access-Control-Request-Headers"
        ));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(maxAge);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}