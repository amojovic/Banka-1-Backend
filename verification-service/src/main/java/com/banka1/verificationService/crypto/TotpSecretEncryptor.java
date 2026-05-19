package com.banka1.verificationService.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;

/**
 * AES-GCM-256 enkripcija TOTP secret-a u DB-u (WP-6, Celina 2.1).
 *
 * <p>Svaka verifikaciona sesija dobija sopstveni Base32 TOTP secret iz kog se
 * racuna 30-sekundni kod. Secret je dovoljan da napadac generise validne kodove,
 * pa se nikada ne sme cuvati u plaintext-u. Ova klasa enkriptuje secret pre
 * upisa u kolonu {@code verification_sessions.totp_secret} i dekriptuje pri
 * citanju — primenjuje se kroz {@link TotpSecretConverter} JPA converter.
 *
 * <p>Po uzoru na {@code com.banka1.security.crypto.JmbgEncryptor}: AES-GCM sa
 * 12-byte IV-om i 128-bitnim authentication tag-om, izlazni format
 * {@code Base64(IV || ciphertext || authTag)}.
 *
 * <p>Kljuc se NE deli sa {@code jwt.secret} — provoze se kroz zaseban env var
 * {@code VERIFICATION_TOTP_ENCRYPTION_KEY} (property {@code verification.totp.encryption-key},
 * 32 bajta = 256 bita, Base64 kodiran). Generisi sa: {@code openssl rand -base64 32}.
 *
 * <p>Dev default je dozvoljen samo u {@code dev}/{@code local}/{@code test}
 * profilu (i u testovima bez aktivnog profila). U produkciji bean odbija da se
 * inicijalizuje ako env var nije eksplicitno postavljen — isti fail-fast ugovor
 * kao {@code JmbgEncryptor} (PR_29).
 */
@Component
public class TotpSecretEncryptor {

    private static final int IV_LENGTH = 12;     // GCM standard
    private static final int TAG_LENGTH = 128;   // bits

    /**
     * Public dev kljuc — ne sme se koristiti u produkciji. Cuva se ovde samo da bi
     * konstruktor mogao da ga prepozna i odbije ako sluzajno procuri u prod env.
     */
    static final String DEV_DEFAULT_KEY_BASE64 = "VG90cERldk9ubHlBRVMyNTZLZXkzMkJ5dGVzISEhNDI=";

    /** Profili u kojima je dozvoljeno pasti na dev default. */
    private static final Set<String> NON_PROD_PROFILES = Set.of("dev", "local", "test");

    private final SecretKeySpec keySpec;
    private final SecureRandom random = new SecureRandom();

    /**
     * Inicijalizuje enkriptor AES-256 kljucem iz konfiguracije.
     *
     * @param base64Key Base64-enkodiran 256-bitni AES kljuc; default je dev kljuc
     *                   (dozvoljen samo van prod profila)
     * @param springEnvironment Spring okruzenje za detekciju aktivnog profila
     * @throws IllegalStateException ako se koristi dev kljuc van dev/local/test profila
     * @throws IllegalArgumentException ako kljuc nije tacno 256 bita
     */
    public TotpSecretEncryptor(
            @Value("${verification.totp.encryption-key:" + DEV_DEFAULT_KEY_BASE64 + "}") String base64Key,
            Environment springEnvironment) {
        boolean isDevProfile = isNonProdProfile(springEnvironment);
        boolean usingDevKey = DEV_DEFAULT_KEY_BASE64.equals(base64Key);

        if (usingDevKey && !isDevProfile) {
            throw new IllegalStateException(
                    "TOTP enkripcioni kljuc nije postavljen, a aktivni profili nisu dev/local/test. "
                            + "Postavi env var VERIFICATION_TOTP_ENCRYPTION_KEY (32 bajta Base64-encoded). "
                            + "Generisi kljuc sa: openssl rand -base64 32");
        }

        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException(
                    "VERIFICATION_TOTP_ENCRYPTION_KEY mora biti tacno 256 bita (32 bajta posle Base64 decode-a).");
        }
        this.keySpec = new SecretKeySpec(keyBytes, "AES");
    }

    private static boolean isNonProdProfile(Environment env) {
        if (env == null) {
            return true; // u testu bez @SpringBootTest dozvoli dev default
        }
        String[] active = env.getActiveProfiles();
        if (active == null || active.length == 0) {
            return true;
        }
        return Arrays.stream(active).anyMatch(NON_PROD_PROFILES::contains);
    }

    /**
     * Enkriptuje plaintext (Base32 TOTP secret) u Base64 ciphertext za DB.
     *
     * @param plaintext secret u plaintext-u; {@code null} prolazi kao {@code null}
     * @return {@code Base64(IV || ciphertext || authTag)} ili {@code null}
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[IV_LENGTH + ct.length];
            System.arraycopy(iv, 0, combined, 0, IV_LENGTH);
            System.arraycopy(ct, 0, combined, IV_LENGTH, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP secret encryption failed", e);
        }
    }

    /**
     * Dekriptuje Base64 ciphertext iz DB-a nazad u plaintext secret.
     *
     * @param encryptedBase64 ciphertext iz DB-a; {@code null} prolazi kao {@code null}
     * @return plaintext Base32 secret ili {@code null}
     */
    public String decrypt(String encryptedBase64) {
        if (encryptedBase64 == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedBase64);
            if (combined.length < IV_LENGTH + TAG_LENGTH / 8) {
                throw new IllegalArgumentException("Encrypted payload too short.");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] ct = new byte[combined.length - IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);
            System.arraycopy(combined, IV_LENGTH, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] pt = cipher.doFinal(ct);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("TOTP secret decryption failed", e);
        }
    }
}
