package com.banka1.tradingservice.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "fund_dividend_distributions",
        uniqueConstraints = @UniqueConstraint(name = "uk_fund_dividend_distribution",
                columnNames = {"fund_id", "stock_ticker", "payment_date"}),
        indexes = {
                @Index(name = "idx_fund_dividend_distribution_fund_id", columnList = "fund_id"),
                @Index(name = "idx_fund_dividend_distribution_payment_date", columnList = "payment_date")
        })
@Getter
@Setter
public class FundDividendDistribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "stock_ticker", nullable = false, length = 16)
    private String stockTicker;

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(name = "dividend_per_share", nullable = false, precision = 19, scale = 8)
    private BigDecimal dividendPerShare;

    @Column(name = "source_currency", nullable = false, length = 8)
    private String sourceCurrency;

    @Column(name = "holding_quantity", nullable = false)
    private Integer holdingQuantity;

    @Column(name = "gross_amount_source", nullable = false, precision = 19, scale = 8)
    private BigDecimal grossAmountSource;

    @Column(name = "gross_amount_rsd", nullable = false, precision = 19, scale = 2)
    private BigDecimal grossAmountRsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", nullable = false, length = 24)
    private FundDividendStrategy strategy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FundDividendDistributionStatus status;

    @Column(name = "reinvested_shares")
    private Integer reinvestedShares;

    @Column(name = "reinvested_amount_rsd", precision = 19, scale = 2)
    private BigDecimal reinvestedAmountRsd;

    @Column(name = "distributed_amount_rsd", precision = 19, scale = 2)
    private BigDecimal distributedAmountRsd;

    @Column(name = "note", length = 255)
    private String note;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt = LocalDateTime.now();
}
