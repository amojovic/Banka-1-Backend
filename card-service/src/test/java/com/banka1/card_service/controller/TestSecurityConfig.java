package com.banka1.card_service.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimal security configuration for {@code @WebMvcTest} slices.
 *
 * <p>The main {@code security-lib} {@code SecurityConfig} is an {@code @AutoConfiguration}
 * class and is not loaded inside a {@code @WebMvcTest} slice, so Spring Security's MVC
 * integration (and therefore the {@code AuthenticationPrincipalArgumentResolver}) is not
 * registered &mdash; a {@code @AuthenticationPrincipal Jwt} parameter would then be treated
 * as a model attribute and fail with {@code BeanInstantiationException}.
 *
 * <p>{@code @EnableWebSecurity} here explicitly imports {@code WebMvcSecurityConfiguration}
 * which registers that resolver. The filter chain is permissive because access is driven by
 * the {@code jwt()} post-processors and ownership checks in the individual tests.
 */
@TestConfiguration
@EnableWebSecurity
public class TestSecurityConfig {

    @Bean
    public SecurityFilterChain testSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
