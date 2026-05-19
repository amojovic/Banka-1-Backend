package com.banka1.verificationService.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testovi za {@link TotpSecretEncryptor} — AES-GCM-256 enkripcija TOTP secret-a
 * at-rest (WP-6, Celina 2.1).
 */
class TotpSecretEncryptorTest {

    /** Validan 32-byte (256-bit) Base64 kljuc, razlicit od dev default-a. */
    private static final String VALID_KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVoxMjM0NTY=";

    private static TotpSecretEncryptor encryptorWithDevKey() {
        // Bez aktivnog profila -> dev default je dozvoljen (kao u testovima).
        return new TotpSecretEncryptor(TotpSecretEncryptor.DEV_DEFAULT_KEY_BASE64, new MockEnvironment());
    }

    @Test
    void encryptThenDecryptReturnsOriginalSecret() {
        TotpSecretEncryptor encryptor = encryptorWithDevKey();

        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
        String ciphertext = encryptor.encrypt(secret);

        assertThat(ciphertext).isNotNull().isNotEqualTo(secret);
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo(secret);
    }

    @Test
    void encryptProducesDifferentCiphertextEachCall() {
        // AES-GCM sa nasumicnim IV-om: isti plaintext -> razlicit ciphertext.
        TotpSecretEncryptor encryptor = encryptorWithDevKey();

        String secret = "SECRETVALUE12345";
        assertThat(encryptor.encrypt(secret)).isNotEqualTo(encryptor.encrypt(secret));
    }

    @Test
    void encryptAndDecryptPassNullThrough() {
        TotpSecretEncryptor encryptor = encryptorWithDevKey();

        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void acceptsExplicitValidKey() {
        TotpSecretEncryptor encryptor = new TotpSecretEncryptor(VALID_KEY, new MockEnvironment());

        String secret = "ANOTHERSECRET999";
        assertThat(encryptor.decrypt(encryptor.encrypt(secret))).isEqualTo(secret);
    }

    @Test
    void rejectsDevKeyOutsideNonProdProfile() {
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        assertThatThrownBy(() -> new TotpSecretEncryptor(TotpSecretEncryptor.DEV_DEFAULT_KEY_BASE64, prodEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VERIFICATION_TOTP_ENCRYPTION_KEY");
    }

    @Test
    void allowsDevKeyInDevProfile() {
        MockEnvironment devEnv = new MockEnvironment();
        devEnv.setActiveProfiles("dev");

        // Ne baca — dev profil dozvoljava dev default.
        TotpSecretEncryptor encryptor =
                new TotpSecretEncryptor(TotpSecretEncryptor.DEV_DEFAULT_KEY_BASE64, devEnv);
        assertThat(encryptor.decrypt(encryptor.encrypt("X"))).isEqualTo("X");
    }

    @Test
    void rejectsKeyOfWrongLength() {
        // 16-byte kljuc Base64-enkodiran -> nije 256 bita.
        String shortKey = "MTIzNDU2Nzg5MDEyMzQ1Ng==";

        assertThatThrownBy(() -> new TotpSecretEncryptor(shortKey, new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bita");
    }
}
