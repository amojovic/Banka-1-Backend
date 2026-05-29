package com.banka1.security_lib;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityAutoConfigurationTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void corsConfigurationAllowsFrontendOrigin() {
        // corsConfigurationSource cita SecurityProperties.Cors blok; ovde koristimo
        // default-e (localhost:4200 + eksplicitne metode/headeri) jer test ne menja config.
        SecurityProperties props = new SecurityProperties();
        CorsConfigurationSource source = securityConfig.corsConfigurationSource(props);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        var configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedOrigins()).containsExactly("http://localhost:4200");
        assertThat(configuration.getAllowedMethods())
                .containsExactly("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(configuration.getAllowedHeaders())
                .containsExactly("Authorization", "Content-Type", "Accept",
                        "X-Requested-With", "X-Verification-Code", "X-Correlation-Id");
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
