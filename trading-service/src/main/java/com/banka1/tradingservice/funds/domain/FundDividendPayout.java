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
import java.time.LocalDateTime;

@Entity
@Table(name = "fund_dividend_payouts",
        uniqueConstraints = @UniqueConstraint(name = "uk_fund_dividend_payout_distribution_client",
                columnNames = {"distribution_id", "client_id"}),
        indexes = {
                @Index(name = "idx_fund_dividend_payout_distribution_id", columnList = "distribution_id"),
                @Index(name = "idx_fund_dividend_payout_client_id", columnList = "client_id")
        })
@Getter
@Setter
public class FundDividendPayout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "distribution_id", nullable = false)
    private Long distributionId;

    @Column(name = "client_id", nullable = false)
    private Long clientId;

    @Column(name = "client_account_number", length = 32)
    private String clientAccountNumber;

    @Column(name = "ownership_ratio", nullable = false, precision = 19, scale = 8)
    private BigDecimal ownershipRatio;

    @Column(name = "amount_rsd", nullable = false, precision = 19, scale = 2)
    private BigDecimal amountRsd;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private FundDividendPayoutStatus status;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
