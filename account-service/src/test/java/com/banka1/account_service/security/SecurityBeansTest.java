package com.banka1.account_service.security;

import com.banka1.security_lib.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Posle PR_19/PR_20 konsolidacije account-service vise nema sopstvenu
 * {@code SecurityBeans} klasu — HS256 {@code JwtDecoder} bean sada dolazi
 * iz shared {@code security-lib} ({@link SecurityConfig#jwtDecoder(String)}).
 * Test je preusmeren na taj izvor istine, ponasanje koje proverava je isto.
 */
class SecurityBeansTest {

    @Test
    void jwtDecoderCreatesDecoderForValidSecret() {
        SecurityConfig securityConfig = new SecurityConfig();

        JwtDecoder jwtDecoder = securityConfig.jwtDecoder("12345678901234567890123456789012");

        assertThat(jwtDecoder).isNotNull();
    }
}

