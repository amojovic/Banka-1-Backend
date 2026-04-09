package com.banka1.stock_service.swagger;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI configuration for {@code stock-service}.
 * Registers the JWT bearer authentication scheme and basic API metadata.
 */
@Configuration
public class SwaggerConfig {

    private static final String APP_TITLE = "Stock Service API";
    private static final String APP_DESCRIPTION = "API for stock exchange and market data management";
    private static final String APP_VERSION = "1.0";

    /**
     * Configures the OpenAPI specification for the service.
     *
     * @return OpenAPI description of the service
     */
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .info(new Info()
                        .title(APP_TITLE)
                        .description(APP_DESCRIPTION)
                        .version(APP_VERSION));
    }
}
