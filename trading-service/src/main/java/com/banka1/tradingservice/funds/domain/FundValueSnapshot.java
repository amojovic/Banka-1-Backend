package com.banka1.tradingservice.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "fund_value_snapshots",
        uniqueConstraints = @UniqueConstraint(name = "uk_fund_value_snapshot_fund_date",
                columnNames = {"fund_id", "snapshot_date"}),
        indexes = {
                @Index(name = "idx_fund_value_snapshots_fund_id", columnList = "fund_id"),
                @Index(name = "idx_fund_value_snapshots_snapshot_date", columnList = "snapshot_date")
        })
@Getter
@Setter
public class FundValueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    @Column(name = "liquidity_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal liquidityValue;

    @Column(name = "holdings_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal holdingsValue;

    @Column(name = "total_value", nullable = false, precision = 19, scale = 2)
    private BigDecimal totalValue;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
