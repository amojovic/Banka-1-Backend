package com.banka1.verificationService.crypto;

import com.banka1.verificationService.model.entity.VerificationSession;
import com.banka1.verificationService.model.enums.OperationType;
import com.banka1.verificationService.model.enums.VerificationStatus;
import com.banka1.verificationService.repository.VerificationSessionRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistence test koji dokazuje da {@link TotpSecretConverter} enkriptuje TOTP
 * secret pre upisa u {@code verification_sessions.totp_secret} kolonu i dekriptuje
 * ga pri citanju (WP-6, Celina 2.1).
 *
 * <p>{@code @DataJpaTest} slice ne skenira {@code @Component} bean-ove, pa se
 * {@link TotpSecretEncryptor} i {@link TotpSecretConverter} eksplicitno uvoze —
 * converter mora biti Spring bean da bi mu se {@code @Autowired} setter pozvao.
 */
@DataJpaTest
@Import({TotpSecretEncryptor.class, TotpSecretConverter.class})
@ActiveProfiles("test")
class TotpSecretConverterPersistenceTest {

    @Autowired
    private VerificationSessionRepository repository;

    @Autowired
    private EntityManager entityManager;

    private VerificationSession newSession(String totpSecret) {
        LocalDateTime now = LocalDateTime.now();
        return VerificationSession.builder()
                .clientId(7L)
                .totpSecret(totpSecret)
                .operationType(OperationType.PAYMENT)
                .relatedEntityId("pay-1")
                .createdAt(now)
                .expiresAt(now.plusMinutes(5))
                .attemptCount(0)
                .status(VerificationStatus.PENDING)
                .build();
    }

    @Test
    void totpSecretIsStoredEncryptedAndReadBackAsPlaintext() {
        String plaintextSecret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        VerificationSession saved = repository.saveAndFlush(newSession(plaintextSecret));
        Long id = saved.getId();

        // Clear the persistence context so the read is a real DB round-trip.
        entityManager.clear();

        // Raw column value (converter bypassed) must be ciphertext, not plaintext.
        Object rawColumn = entityManager
                .createNativeQuery("SELECT totp_secret FROM verification_sessions WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        assertThat(rawColumn).isInstanceOf(String.class);
        assertThat((String) rawColumn).isNotEqualTo(plaintextSecret);

        // Reading through JPA (converter applied) yields the original plaintext.
        VerificationSession reloaded = repository.findById(id).orElseThrow();
        assertThat(reloaded.getTotpSecret()).isEqualTo(plaintextSecret);
    }

    @Test
    void nullTotpSecretIsPersistedAsNull() {
        VerificationSession saved = repository.saveAndFlush(newSession(null));
        entityManager.clear();

        VerificationSession reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTotpSecret()).isNull();
    }
}
