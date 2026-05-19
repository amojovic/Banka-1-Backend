package com.banka1.tradingservice.funds.dto;

import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO za investicioni fond. {@code totalValue} i {@code profit} su izvedeni
 * (racunaju se per query u servisu) — vidi spec.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestmentFundDto {
    private Long id;
    private String naziv;
    private String opis;
    private BigDecimal minimumContribution;
    private Long managerId;
    private String managerIme;
    private String managerPrezime;
    private BigDecimal likvidnaSredstva;
    private Long accountId;
    private String accountNumber;
    private LocalDate datumKreiranja;
    /** Izvedeno: likvidnaSredstva + suma vrednosti hartija. */
    private BigDecimal totalValue;
    /** Izvedeno: totalValue - sumOfClientInvestments. */
    private BigDecimal profit;
    /** WP-17 (Celina 4.3): politika obrade dividende fonda ({@code REINVEST}/{@code DISTRIBUTE}). */
    private FundDividendPolicy dividendPolicy;

    // --- WP-18 (Celina 4.4): statistika performansi. Sva cetiri polja su
    //     nullable — null kada fond ima manje od minimalnog broja snapshot-a
    //     (FundStatisticsService.MIN_SNAPSHOTS_FOR_STATS). Frakcije, ne procenti.

    /** WP-18: godisnji (anualizovan) prinos kao frakcija; {@code null} ispod minimuma snapshot-a. */
    private BigDecimal annualizedReturn;
    /** WP-18: reward-to-variability (Sharpe-like); {@code null} ispod minimuma snapshot-a ili pri nultoj volatilnosti. */
    private BigDecimal rewardToVariabilityRatio;
    /** WP-18: max drawdown kao frakcija ({@code >= 0}); {@code null} ispod minimuma snapshot-a. */
    private BigDecimal maxDrawdown;
    /** WP-18: volatilnost (uzoracka std. dev. mesecnih prinosa); {@code null} ispod minimuma snapshot-a. */
    private BigDecimal volatility;
}
