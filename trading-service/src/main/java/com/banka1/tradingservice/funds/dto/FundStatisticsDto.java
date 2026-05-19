package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * WP-18 (Celina 4.4): statistika performansi investicionog fonda izvedena iz
 * serije {@link com.banka1.tradingservice.funds.domain.FundValueSnapshot} redova.
 *
 * <p>Sve metrike su <b>frakcije</b> (npr. {@code 0.12} = 12%), ne procenti —
 * frontend ih formatira. Reward-to-variability je bezdimenziona.
 *
 * <p>Metrike imaju smisla tek kad postoji dovoljno istorije: ispod
 * {@code FundStatisticsService.MIN_SNAPSHOTS_FOR_STATS} snapshot-a
 * {@code metricsAvailable=false} i sve metrike su {@code null}.
 *
 * @see com.banka1.tradingservice.funds.service.FundStatisticsService
 */
@Data
@Builder
public class FundStatisticsDto {

    /** ID fonda na koji se statistika odnosi. */
    private Long fundId;

    /**
     * Da li je bilo dovoljno snapshot-a da se metrike izracunaju. Kad je
     * {@code false}, sve cetiri metrike su {@code null}.
     */
    private boolean metricsAvailable;

    /** Broj snapshot-a u seriji (osnova obracuna). */
    private int snapshotCount;

    /** Minimalan broj snapshot-a potreban da se metrike racunaju. */
    private int minSnapshotsRequired;

    /**
     * Godisnji prinos (frakcija) — anualizovan ukupan prinos preko posmatranog
     * perioda: {@code (v[last]/v[0])^(12/numMonths) - 1}.
     */
    private BigDecimal annualizedReturn;

    /**
     * Reward-to-variability (Sharpe-like, bezdimenziono) — {@code prosecan
     * mesecni prinos / volatilnost}, uz risk-free stopu 0. {@code null} kad je
     * volatilnost 0 (deljenje nulom).
     */
    private BigDecimal rewardToVariabilityRatio;

    /**
     * Max drawdown (frakcija, >= 0) — najveci pad od tekuceg vrha do dna:
     * {@code max nad j od (runningPeak[j] - v[j]) / runningPeak[j]}.
     */
    private BigDecimal maxDrawdown;

    /** Volatilnost (frakcija, >= 0) — uzoracka standardna devijacija mesecnih prinosa. */
    private BigDecimal volatility;
}
