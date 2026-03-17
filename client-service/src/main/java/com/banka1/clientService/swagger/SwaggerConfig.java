package com.banka1.clientService.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Konfiguracija Swagger/OpenAPI dokumentacije za client-service.
 * URL servera se ucitava iz konfiguracije kako bi bio zamenljiv po okruzenju.
 * Aplikacija pada pri pokretanju ako CLIENT_SERVICE_URL nije postavljen.
 */
@Configuration
public class SwaggerConfig {

    /**
     * URL servera koji se prikazuje u generisanoj OpenAPI specifikaciji.
     * Prazan string kao default kako bi @PostConstruct mogao da da jasnu poruku o gresci
     * umesto generickog "Could not resolve placeholder".
     */
    @Value("${springdoc.server-url:}")
    private String serverUrl;

    /**
     * Eksplicitna validacija pri pokretanju aplikacije.
     * Pada sa jasnom porukom ako CLIENT_SERVICE_URL env varijabla nije postavljena.
     */
    @PostConstruct
    void validate() {
        if (serverUrl.isBlank()) {
            throw new IllegalStateException(
                    "CLIENT_SERVICE_URL env varijabla nije postavljena — " +
                    "aplikacija ne moze da se pokrene bez poznatog URL-a servera."
            );
        }
    }

    /**
     * Kreira OpenAPI konfiguraciju sa JWT Bearer autentifikacijom i konfigurisanim URL serverom.
     *
     * @return konfigurisani OpenAPI objekat
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addServersItem(new Server().url(serverUrl))
                .info(new Info()
                        .title("Client Service API")
                        .description("Servis za upravljanje klijentima banke. Dostupan samo zaposlenima.")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList("BearerAuthentication"))
                .components(new Components()
                        .addSecuritySchemes("BearerAuthentication",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
