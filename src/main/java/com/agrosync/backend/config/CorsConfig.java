package com.agrosync.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class CorsConfig implements WebMvcConfigurer {
    private final String frontendOrigin;

    CorsConfig(@Value("${AGROSYNC_FRONTEND_ORIGIN:http://127.0.0.1:5173}") String frontendOrigin) {
        this.frontendOrigin = frontendOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/v1/**")
                .allowedOrigins(frontendOrigin)
                .allowedMethods("GET", "POST")
                .allowedHeaders("Content-Type", "X-CSRF-TOKEN")
                .exposedHeaders("X-AgroSync-Logout-Url")
                .allowCredentials(true);
    }
}
