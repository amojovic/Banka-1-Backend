package com.banka1.tradingservice.dividend.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * WP-14 (Celina 3.7): konfiguracija za dividend scheduling.
 *
 * <p>Izlaze {@link Clock} bean koji {@code DividendScheduler} koristi za
 * odredjivanje "danasnjeg" datuma. Izdvajanje sata u bean cini gejt-predikat
 * "poslednji radni dan kvartala" testabilnim (test ubaci fiksni
 * {@link Clock#fixed}).
 *
 * <p>{@code @ConditionalOnMissingBean} osigurava da se ne sudara ako neki drugi
 * modul (sada ili kasnije) registruje sopstveni {@code Clock}.
 */
@Configuration
public class DividendSchedulingConfig {

    /**
     * @return sistemski sat u podrazumevanoj vremenskoj zoni
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock dividendClock() {
        return Clock.systemDefaultZone();
    }
}
