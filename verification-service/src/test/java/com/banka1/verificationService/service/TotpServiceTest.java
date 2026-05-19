package com.banka1.verificationService.service;

import com.banka1.verificationService.config.VerificationOtpProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testira {@link TotpService} protiv RFC 6238 referentnih vektora i clock-skew
 * tolerancije (WP-6, Celina 2.1).
 *
 * <p>RFC 6238 Appendix B referentni secret je ASCII {@code "12345678901234567890"}.
 * Biblioteka {@code dev.samstevens.totp} ocekuje Base32-enkodiran secret, pa se
 * koristi Base32 tog ASCII stringa: {@code GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ}.
 * Na Unix vremenu 59s (T0=0, korak 30s -> brojac 1) SHA-1 TOTP je {@code 94287082},
 * odnosno {@code 287082} skraceno na 6 cifara.
 */
class TotpServiceTest {

    /** Base32(ASCII "12345678901234567890") — RFC 6238 Appendix B SHA-1 secret. */
    private static final String RFC6238_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    /** Ocekivani 6-cifreni TOTP za RFC6238_SECRET na Unix vremenu 59s. */
    private static final String RFC6238_CODE_AT_59S = "287082";

    private static VerificationOtpProperties defaultProperties() {
        return new VerificationOtpProperties(); // ttl=5, attempts=3, step=30, window=1
    }

    private static Clock fixedClockAtEpochSeconds(long epochSeconds) {
        return Clock.fixed(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }

    @Test
    void currentCodeMatchesKnownRfc6238Vector() {
        TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(59L));

        assertThat(service.currentCode(RFC6238_SECRET)).isEqualTo(RFC6238_CODE_AT_59S);
    }

    @Test
    void verifyAcceptsCodeFromKnownRfc6238Vector() {
        TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(59L));

        assertThat(service.verify(RFC6238_SECRET, RFC6238_CODE_AT_59S)).isTrue();
    }

    @Test
    void currentCodeIsAlwaysSixDigits() {
        // Razlicita vremena -> kod uvek tacno 6 cifara (sa vodecim nulama).
        for (long t = 0; t < 600; t += 37) {
            TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(t));
            assertThat(service.currentCode(RFC6238_SECRET)).matches("^\\d{6}$");
        }
    }

    @Test
    void verifyRejectsWrongCode() {
        TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(59L));

        assertThat(service.verify(RFC6238_SECRET, "000000")).isFalse();
    }

    @Test
    void verifyAcceptsCodeOneStepInThePast() {
        // Kod izracunat u koraku N mora i dalje da prodje verifikaciju kada
        // server-ov casovnik odmakne za jedan korak (30s) — clock-skew tolerancija.
        long codeGeneratedAt = 59L;
        TotpService generator = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt));
        String code = generator.currentCode(RFC6238_SECRET);

        TotpService laterServer = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt + 30L));

        assertThat(laterServer.verify(RFC6238_SECRET, code)).isTrue();
    }

    @Test
    void verifyAcceptsCodeOneStepInTheFuture() {
        // Kod iz buduceg koraka (klijent ispred servera za jedan korak) prolazi.
        long codeGeneratedAt = 119L;
        TotpService generator = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt));
        String code = generator.currentCode(RFC6238_SECRET);

        TotpService earlierServer = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt - 30L));

        assertThat(earlierServer.verify(RFC6238_SECRET, code)).isTrue();
    }

    @Test
    void verifyRejectsCodeTwoStepsAway() {
        // Sa window=1, kod udaljen 2 koraka (60s) je van tolerancije.
        long codeGeneratedAt = 59L;
        TotpService generator = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt));
        String code = generator.currentCode(RFC6238_SECRET);

        TotpService farServer = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(codeGeneratedAt + 90L));

        assertThat(farServer.verify(RFC6238_SECRET, code)).isFalse();
    }

    @Test
    void verifyRejectsNullSecretOrCode() {
        TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(59L));

        assertThat(service.verify(null, RFC6238_CODE_AT_59S)).isFalse();
        assertThat(service.verify(RFC6238_SECRET, null)).isFalse();
    }

    @Test
    void generateSecretProducesDistinctNonBlankSecrets() {
        TotpService service = new TotpService(defaultProperties(), fixedClockAtEpochSeconds(59L));

        String first = service.generateSecret();
        String second = service.generateSecret();

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank();
        assertThat(first).isNotEqualTo(second);
        // Generisani secret se moze odmah iskoristiti za generate/verify ciklus.
        assertThat(service.verify(first, service.currentCode(first))).isTrue();
    }

    @Test
    void widerWindowAcceptsCodesFurtherAway() {
        // Sa window=2, kod udaljen 2 koraka sada prolazi.
        VerificationOtpProperties wideWindow = new VerificationOtpProperties();
        wideWindow.setWindow(2);

        long codeGeneratedAt = 59L;
        TotpService generator = new TotpService(wideWindow, fixedClockAtEpochSeconds(codeGeneratedAt));
        String code = generator.currentCode(RFC6238_SECRET);

        TotpService farServer = new TotpService(wideWindow, fixedClockAtEpochSeconds(codeGeneratedAt + 60L));

        assertThat(farServer.verify(RFC6238_SECRET, code)).isTrue();
    }
}
