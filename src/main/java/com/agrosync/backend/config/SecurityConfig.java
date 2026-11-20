package com.agrosync.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Configuration(proxyBeanMethods = false)
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http,
                                 @Value("${AGROSYNC_CRM_PUBLIC_URL:http://127.0.0.1:8080}") URI crmPublic) throws Exception {
        var crmLogout = UriComponentsBuilder.fromUri(crmPublic).path("/logout").build().toUriString();
        return http.cors(Customizer.withDefaults())
                .requestCache(cache -> cache.disable())
                .authorizeHttpRequests(rules -> rules
                        .requestMatchers("/actuator/health", "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/callback").permitAll()
                        .anyRequest().authenticated())
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, auth) -> {
                            response.setHeader("Cache-Control", "no-store");
                            response.setHeader("X-AgroSync-Logout-Url", crmLogout);
                            response.setStatus(204);
                        }))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, e) -> error(response, 401, "AUTENTICACION_REQUERIDA", "Inicia sesión."))
                        .accessDeniedHandler((request, response, e) -> error(response, 403, "ACCESO_DENEGADO", "Acceso denegado.")))
                .build();
    }

    private static void error(jakarta.servlet.http.HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
