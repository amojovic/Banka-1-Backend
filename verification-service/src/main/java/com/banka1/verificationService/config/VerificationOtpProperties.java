package com.banka1.verificationService.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Konfiguracija verifikacionog (TOTP) koda — prefix {@code verification.otp} (WP-6, Celina 2.1).
 *
 * <p>Property-ji {@code verification.otp.ttl-minutes} i {@code verification.otp.max-attempts}
 * vec postoje u {@code banking-core-service/application.properties} ali ih do WP-6
 * nije citala nijedna klasa. Ova {@code @ConfigurationProperties} klasa ih veze i
 * dodaje dva nova kljuca za standardni TOTP (RFC 6238): {@code time-step-seconds}
 * i {@code window}.
 *
 * <p>Vrednosti imaju default-e tako da servis radi i ako properties nisu eksplicitno
 * postavljeni (npr. u testovima): TTL 5 min, 3 pokusaja, 30-sekundni TOTP korak,
 * tolerancija +/-1 koraka za clock-skew izmedju klijenta i servera.
 *
 * <p>Registruje se kroz {@code @ConfigurationPropertiesScan} (banking-core i
 * standalone verification-service ga oba imaju).
 */
@ConfigurationProperties(prefix = "verification.otp")
public class VerificationOtpProperties {

    /** Default vazenje verifikacione sesije u minutima. */
    private static final int DEFAULT_TTL_MINUTES = 5;
    /** Default broj dozvoljenih neuspelih pokusaja pre otkazivanja sesije. */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    /** Default TOTP vremenski korak u sekundama (RFC 6238 preporuka). */
    private static final int DEFAULT_TIME_STEP_SECONDS = 30;
    /** Default tolerancija u broju koraka na svaku stranu (clock-skew). */
    private static final int DEFAULT_WINDOW = 1;

    /**
     * Koliko dugo (u minutima) verifikaciona sesija ostaje validna od kreiranja.
     * Posle isteka sesija prelazi u EXPIRED. Default {@value #DEFAULT_TTL_MINUTES}.
     */
    private int ttlMinutes = DEFAULT_TTL_MINUTES;

    /**
     * Broj neuspelih validacionih pokusaja posle kojih se sesija otkazuje
     * (status CANCELLED). Default {@value #DEFAULT_MAX_ATTEMPTS}.
     */
    private int maxAttempts = DEFAULT_MAX_ATTEMPTS;

    /**
     * TOTP vremenski korak u sekundama — duzina prozora u kome jedan kod vazi.
     * Spec (Celina 2.1) trazi 30 sekundi. Default {@value #DEFAULT_TIME_STEP_SECONDS}.
     */
    private int timeStepSeconds = DEFAULT_TIME_STEP_SECONDS;

    /**
     * Tolerancija na desinhronizaciju casovnika izmedju klijenta i servera,
     * izrazena u broju TOTP koraka na svaku stranu. {@code window=1} prihvata
     * prethodni, trenutni i sledeci korak. Default {@value #DEFAULT_WINDOW}.
     */
    private int window = DEFAULT_WINDOW;

    /**
     * @return vazenje sesije u minutima
     */
    public int getTtlMinutes() {
        return ttlMinutes;
    }

    /**
     * @param ttlMinutes vazenje sesije u minutima
     */
    public void setTtlMinutes(int ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * @return broj dozvoljenih neuspelih pokusaja
     */
    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * @param maxAttempts broj dozvoljenih neuspelih pokusaja
     */
    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    /**
     * @return TOTP vremenski korak u sekundama
     */
    public int getTimeStepSeconds() {
        return timeStepSeconds;
    }

    /**
     * @param timeStepSeconds TOTP vremenski korak u sekundama
     */
    public void setTimeStepSeconds(int timeStepSeconds) {
        this.timeStepSeconds = timeStepSeconds;
    }

    /**
     * @return tolerancija u broju TOTP koraka na svaku stranu
     */
    public int getWindow() {
        return window;
    }

    /**
     * @param window tolerancija u broju TOTP koraka na svaku stranu
     */
    public void setWindow(int window) {
        this.window = window;
    }
}
