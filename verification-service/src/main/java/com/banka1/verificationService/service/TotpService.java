package com.banka1.verificationService.service;

import com.banka1.verificationService.config.VerificationOtpProperties;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Standardni TOTP servis (RFC 6238) za verifikacione kodove (WP-6, Celina 2.1).
 *
 * <p>Spec (Celina 2.1): "Verifikacioni kod — preporuka je standardna TOTP metoda,
 * trajanje 30 sekundi; voditi racuna o sinhronizaciji vremena izmedju klijenta i
 * servera." Ovaj servis:
 * <ul>
 *   <li>generise per-session Base32 secret ({@link #generateSecret()}),</li>
 *   <li>racuna trenutni 6-cifreni TOTP kod za dati secret ({@link #currentCode(String)}),</li>
 *   <li>verifikuje uneti kod uz toleranciju od +/-{@code window} koraka radi
 *       desinhronizacije casovnika ({@link #verify(String, String)}).</li>
 * </ul>
 *
 * <p>Koristi {@code dev.samstevens.totp} (SHA1, 6 cifara). Vremenski korak i
 * window dolaze iz {@link VerificationOtpProperties}. {@link Clock} se injektuje
 * (default {@code Clock.systemUTC()}) tako da testovi mogu da fiksiraju vreme i
 * provere kod protiv poznatih RFC 6238 vektora.
 */
@Service
public class TotpService {

    /** Broj cifara TOTP koda — spec i DTO {@code @Pattern} traze 6. */
    private static final int CODE_DIGITS = 6;

    private final VerificationOtpProperties otpProperties;
    private final Clock clock;
    private final SecretGenerator secretGenerator;
    private final CodeGenerator codeGenerator;
    private final CodeVerifier codeVerifier;

    /**
     * Konstruise TOTP servis sa sistemskim UTC casovnikom.
     *
     * <p>Spring koristi ovaj konstruktor — {@code Clock} nije Spring bean, pa se
     * ovde fiksira na {@code Clock.systemUTC()}. Testovi koriste
     * {@link #TotpService(VerificationOtpProperties, Clock)} sa fiksnim casovnikom.
     *
     * @param otpProperties konfiguracija TOTP koraka i window-a
     */
    @Autowired
    public TotpService(VerificationOtpProperties otpProperties) {
        this(otpProperties, Clock.systemUTC());
    }

    /**
     * Konstruise TOTP servis sa eksplicitnim casovnikom (za deterministicke testove).
     *
     * @param otpProperties konfiguracija TOTP koraka i window-a
     * @param clock izvor vremena; TOTP korak se racuna iz {@code clock.millis()}
     */
    public TotpService(VerificationOtpProperties otpProperties, Clock clock) {
        this.otpProperties = otpProperties;
        this.clock = clock;
        this.secretGenerator = new DefaultSecretGenerator();
        this.codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, CODE_DIGITS);
        TimeProvider timeProvider = new ClockBackedTimeProvider(clock);
        DefaultCodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
        verifier.setTimePeriod(otpProperties.getTimeStepSeconds());
        verifier.setAllowedTimePeriodDiscrepancy(otpProperties.getWindow());
        this.codeVerifier = verifier;
    }

    /**
     * Generise novi nasumicni Base32 TOTP secret za jednu sesiju.
     *
     * @return Base32-enkodiran secret
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Racuna trenutni 6-cifreni TOTP kod za dati secret na osnovu injektovanog
     * casovnika i konfigurisanog vremenskog koraka.
     *
     * @param secret Base32 TOTP secret
     * @return 6-cifreni TOTP kod kao string (sa vodecim nulama)
     * @throws IllegalStateException ako generisanje koda ne uspe
     */
    public String currentCode(String secret) {
        try {
            return codeGenerator.generate(secret, currentTimeStep());
        } catch (CodeGenerationException ex) {
            throw new IllegalStateException("Unable to generate TOTP code.", ex);
        }
    }

    /**
     * Verifikuje uneti kod protiv secret-a uz toleranciju od +/-{@code window}
     * vremenskih koraka (clock-skew tolerancija).
     *
     * @param secret Base32 TOTP secret sesije
     * @param submittedCode kod koji je korisnik uneo
     * @return {@code true} ako je kod validan u dozvoljenom prozoru
     */
    public boolean verify(String secret, String submittedCode) {
        if (secret == null || submittedCode == null) {
            return false;
        }
        return codeVerifier.isValidCode(secret, submittedCode);
    }

    /**
     * Trenutni TOTP brojac (counter) = epoch sekunde / vremenski korak.
     *
     * @return indeks tekuceg vremenskog koraka
     */
    private long currentTimeStep() {
        return clock.millis() / 1000L / otpProperties.getTimeStepSeconds();
    }

    /**
     * {@link TimeProvider} adapter koji vreme cita iz injektovanog {@link Clock}-a
     * umesto iz {@code System.currentTimeMillis()}. Omogucava deterministicke
     * testove TOTP verifikacije sa fiksnim casovnikom.
     */
    private static final class ClockBackedTimeProvider implements TimeProvider {

        private final Clock clock;

        private ClockBackedTimeProvider(Clock clock) {
            this.clock = clock;
        }

        @Override
        public long getTime() {
            return clock.millis() / 1000L;
        }
    }
}
