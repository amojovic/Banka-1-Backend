package com.banka1.transfer.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Konfiguraciona klasa za Spring Security bean-ove zadužene za validaciju tokena.
 * Omogućava OAuth2 Resource Server-u da dekodira dolazne JWT tokene i verifikuje njihov potpis.
 */
@Configuration
@EnableMethodSecurity
public class SecurityBeans {

    /**
     * Kreira {@link JwtDecoder} bean koji verifikuje HMAC potpis dolaznih tokena.
     * Koristi istu deljenu tajnu kao i ostali mikroservisi u sistemu.
     * @param secret tajna za verifikaciju potpisa učitana iz 'jwt.secret' propertija
     * @return konfigurisan NimbusJwtDecoder
     */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${jwt.secret}") String secret) {
        SecretKey key = new SecretKeySpec(secret.getBytes(), "HmacSHA256");
        return NimbusJwtDecoder.withSecretKey(key).build();
    }
}
