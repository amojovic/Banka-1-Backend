package app.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.Map;

/**
 * Security configuration for the {@link InAppNotificationController}
 * {@code @WebMvcTest} slice.
 *
 * <p>The production {@code security-lib} {@code SecurityConfig} is an
 * {@code @AutoConfiguration} class and is not loaded inside a {@code @WebMvcTest}
 * slice, so this test config rebuilds the parts the controller depends on:
 * <ul>
 *   <li>{@code @EnableWebSecurity} registers the
 *       {@code AuthenticationPrincipalArgumentResolver} so a
 *       {@code @AuthenticationPrincipal Jwt} parameter resolves;</li>
 *   <li>the filter chain requires authentication so unauthenticated requests
 *       get an honest HTTP 401 (matching production behaviour) rather than
 *       reaching the controller with a {@code null} principal;</li>
 *   <li>an oauth2 resource server is wired so the {@code jwt()} request
 *       post-processor produces an authenticated {@link Jwt} principal.</li>
 * </ul>
 */
@TestConfiguration
@EnableWebSecurity
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> { }));
        return http.build();
    }

    /**
     * Stub decoder so the resource-server chain can be built. The actual tests
     * inject the principal via the {@code jwt()} post-processor and never hit
     * this decoder; it exists only to satisfy bean wiring.
     *
     * @return a no-op decoder that yields a minimal token
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return token -> Jwt.withTokenValue(token)
                .header("alg", "none")
                .claim("sub", "test")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}
