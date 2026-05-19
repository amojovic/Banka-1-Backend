--liquibase formatted sql

-- changeset jovan:7
-- WP-6 (Celina 2.1): standardni per-session TOTP (RFC 6238) zamenjuje nasumicni
-- emailovani kod. Sesija sada cuva svoj sopstveni Base32 TOTP secret iz kog se
-- racuna 30-sekundni kod. Secret se NIKADA ne cuva u plaintext-u — kolona drzi
-- AES-GCM-256 ciphertext (Base64), enkripciju radi TotpSecretConverter
-- (JPA AttributeConverter) sa kljucem iz verification.totp.encryption-key.
--
-- Kolona 'totp_secret' je nullable: postojeci (legacy) red-ovi nastali pre WP-6
-- nemaju secret i koriste stari hash u koloni 'code'. Novi red-ovi uvek dobijaju
-- totp_secret. Kolona 'code' se NE brise (back-compat sa starim PENDING sesijama),
-- ali joj se SKIDA NOT NULL ogranicenje (001/002 su je kreirali kao NOT NULL) —
-- nove TOTP sesije ostavljaju 'code' prazno, pa bi insert pao bez ovoga.

ALTER TABLE verification_sessions
    ADD COLUMN totp_secret VARCHAR(255);

ALTER TABLE verification_sessions
    ALTER COLUMN code DROP NOT NULL;

-- rollback ALTER TABLE verification_sessions ALTER COLUMN code SET NOT NULL;
-- rollback ALTER TABLE verification_sessions DROP COLUMN totp_secret;
