package com.banka1.clientService.swagger;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Konfiguracija Swagger/OpenAPI dokumentacije za client-service.
 */
@Configuration
public class SwaggerConfig {

    /**
     * Kreira OpenAPI konfiguraciju sa JWT Bearer autentifikacijom.
     *
     * @return konfigurisani OpenAPI objekat
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Client Service API")
                        .description("Servis za upravljanje klijentima banke. Dostupan samo zaposlenima.")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("Bearer Authentication"))
                .components(new Components()
                        .addSecuritySchemes("Bearer Authentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
