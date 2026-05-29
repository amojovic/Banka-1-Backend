package com.banka1.clientService.config;

import com.banka1.security.crypto.JmbgEncryptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * Registruje {@link JmbgEncryptor} kao Spring bean u standalone {@code client-service}-u.
 *
 * <p>{@code JmbgEncryptor} je {@code @Component} u paketu {@code com.banka1.security.crypto}
 * (modul {@code security-lib}). Konsolidovani {@code user-service} ga pokupi svojim
 * {@code @ComponentScan("com.banka1")}, ali standalone {@code ClientServiceApplication}
 * scan-uje samo {@code com.banka1.clientService}, pa enkriptor nije vidljiv. Bez ovog
 * bean-a {@code JmbgPlaintextToCiphertextMigrator} ne moze da se instancira i context
 * ne moze da se podigne.
 *
 * <p>Eksplicitan {@code @Bean} (umesto sirenja {@code @ComponentScan}-a na
 * {@code com.banka1.security}) je namerno odabran jer prosirivanje scan-a na nivou
 * aplikacione klase razbija {@code @WebMvcTest} slice-ove — slice tada uvuce i
 * servisni sloj koji zahteva repozitorijume.
 *
 * <p>Bean je {@code @Primary}: u konsolidovanom {@code user-service}-u
 * {@code @ComponentScan("com.banka1")} vec registruje {@code JmbgEncryptor} kao
 * {@code @Component} iz {@code security-lib}-a, pa bi bez {@code @Primary} dosao
 * do {@code NoUniqueBeanDefinitionException}. {@code @Primary} resava izbor
 * deterministicki bez obzira na redosled skeniranja.
 */
@Configuration
public class JmbgEncryptorConfig {

    /**
     * Dev-only AES kljuc (256-bit, Base64). Identican je package-private
     * {@code JmbgEncryptor.DEV_DEFAULT_KEY_BASE64} konstanti — duplira se ovde jer
     * je originalna konstanta package-private za {@code com.banka1.security.crypto}.
     * {@code JmbgEncryptor} konstruktor odbija ovaj kljuc van dev/local/test profila.
     */
    private static final String DEV_DEFAULT_KEY_BASE64 =
            "VGhpc0lzQURldk9ubHkzMkJ5dGVBRVNLZXktMTIzNDU=";

    /**
     * Kreira {@link JmbgEncryptor} koristeci isti konstruktor-ugovor kao
     * {@code security-lib}: AES kljuc iz {@code banka.security.jmbg-aes-key}
     * (dev default je dozvoljen samo u dev/local/test profilu) i {@link Environment}
     * za detekciju aktivnog profila.
     *
     * @param base64Key Base64-enkodiran 256-bitni AES kljuc; default je dev kljuc
     * @param environment Spring okruzenje za proveru aktivnog profila
     * @return inicijalizovan {@link JmbgEncryptor}
     */
    @Bean
    @Primary
    public JmbgEncryptor jmbgEncryptor(
            @Value("${banka.security.jmbg-aes-key:" + DEV_DEFAULT_KEY_BASE64 + "}") String base64Key,
            Environment environment) {
        return new JmbgEncryptor(base64Key, environment);
    }
}
