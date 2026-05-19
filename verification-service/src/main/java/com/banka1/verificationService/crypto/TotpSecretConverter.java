package com.banka1.verificationService.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter za transparentnu enkripciju TOTP secret-a (WP-6, Celina 2.1).
 *
 * <p>Primenjuje se na {@code VerificationSession.totpSecret}:
 * <pre>
 *   &#64;Convert(converter = TotpSecretConverter.class)
 *   private String totpSecret;
 * </pre>
 *
 * <p>Hibernate enkriptuje pri INSERT/UPDATE i dekriptuje pri SELECT. Servisni
 * sloj radi sa plaintext Base32 secret-om, DB cuva AES-GCM-256 ciphertext.
 *
 * <p>Po uzoru na {@code com.banka1.security.crypto.JmbgConverter}: enkriptor se
 * drzi u {@code static} polju jer Hibernate instancira converter van Spring
 * konteksta — {@code @Autowired} setter injektuje bean cim kontekst nastane.
 */
@Component
@Converter(autoApply = false)
public class TotpSecretConverter implements AttributeConverter<String, String> {

    private static TotpSecretEncryptor encryptor;

    /**
     * Spring injektuje {@link TotpSecretEncryptor} u static polje.
     *
     * @param enc enkriptor iz Spring konteksta
     */
    @Autowired
    public void setEncryptor(TotpSecretEncryptor enc) {
        TotpSecretConverter.encryptor = enc;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) {
            return null;
        }
        return encryptor.encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        return encryptor.decrypt(dbData);
    }
}
