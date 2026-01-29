package com.vivumate.coreapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI(@Value("${vivumate.openapi.dev-url}") String devUrl) {
        return new OpenAPI()
                .info(new Info()
                        .title("ViVuMate Core API")
                        .version("1.0.0")
                        .description("Standard API documentation for the ViVuMate application. Full support for Authentication, RBAC, and Travel Management features.")
                        .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0"))
                        .contact(new Contact().name("ViVuMate Team").email("buitrungkien2005qng@gmail.com")))
                .servers(List.of(
                        new Server().url(devUrl).description("Development Environment Server"),
                        new Server().url("https://api.vivumate.com").description("Production Environment Server")
                ))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", createAPIKeyScheme()));
    }

    private SecurityScheme createAPIKeyScheme() {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .bearerFormat("JWT")
                .scheme("bearer")
                .description("Enter JWT token in format: Bearer <token>");
    }
}