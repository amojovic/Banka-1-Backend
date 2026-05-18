package com.banka1.transfer.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Minimalna security konfiguracija za @WebMvcTest slice-ove.
 * <p>
 * Glavni {@code security-lib} {@code SecurityConfig} je {@code @AutoConfiguration}
 * klasa i ne ucitava se u @WebMvcTest slice-u, pa Spring Security MVC integracija
 * (a time i {@code AuthenticationPrincipalArgumentResolver}) nije registrovana —
 * {@code @AuthenticationPrincipal Jwt} parametar bi se tada tretirao kao model
 * atribut i padao sa {@code BeanInstantiationException}.
 * <p>
 * {@code @EnableWebSecurity} ovde eksplicitno uvozi {@code WebMvcSecurityConfiguration}
 * koji registruje resolver. Filter chain je permisivan jer autorizacija na nivou
 * metode ({@code @PreAuthorize}) i {@code @WithMockUser}/{@code jwt()} post-procesori
 * iz pojedinacnih testova kontrolisu pristup.
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
