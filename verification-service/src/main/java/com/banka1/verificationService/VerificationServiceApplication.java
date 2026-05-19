package com.banka1.verificationService;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Main entry point za Verification Service.
 * Ovaj servis upravlja 2FA verifikacionim sesijama za potvrdu transakcija klijenata
 * (placanja, transferi, promene limita, zahtevi za kartice/kredite).
 *
 * <p>WP-6 (Celina 2.1): {@code @ConfigurationPropertiesScan} veze
 * {@code VerificationOtpProperties} (prefix {@code verification.otp}) i u standalone
 * rezimu — konsolidovani banking-core ima sopstveni scan.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class VerificationServiceApplication {

    /**
     * Pokretanje Spring Boot aplikacije.
     *
     * @param args argumenti komandne linije
     */
    public static void main(String[] args) {
        SpringApplication.run(VerificationServiceApplication.class, args);
    }
}
