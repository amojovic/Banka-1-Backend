package com.banka1.tradingservice.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WP-18 (Celina 4.4): snapshot vrednosti investicionog fonda na odredjeni datum.
 *
 * <p>{@code vrednostFonda} i {@code profit} su inace izvedeni — racunaju se per
 * query u {@code InvestmentFundService} ({@code likvidnaSredstva + vrednost
 * hartija}; profit {@code = totalValue - Sigma totalInvested}) i NE cuvaju se na
 * {@link InvestmentFund}. Da bi statistika fonda (godisnji prinos, volatilnost,
 * max drawdown, reward-to-variability) imala istorijsku osnovu, mesecni
 * {@code FundSnapshotScheduler} materijalizuje tu izvedenu vrednost u jedan red
 * ove tabele po fondu po danu.
 *
 * <p>UNIQUE {@code (fund_id, snapshot_date)} — najvise jedan snapshot po fondu
 * po datumu; scheduler je idempotentan na tom paru.
 *
 * <p>Tabelu definise Liquibase changeset {@code trading-otc/016-fund-value-snapshots.sql};
 * mapiranje mora tacno odgovarati semi jer trading-service radi sa
 * {@code spring.jpa.hibernate.ddl-auto=validate}.
 */
@Entity
@Table(
        name = "fund_value_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_fund_value_snapshot_fund_date",
                columnNames = {"fund_id", "snapshot_date"}),
        indexes = {
                @Index(name = "idx_fund_value_snapshots_fund_id", columnList = "fund_id"),
                @Index(name = "idx_fund_value_snapshots_snapshot_date", columnList = "snapshot_date")
        })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FundValueSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID fonda ({@link InvestmentFund#getId()}) ciju vrednost ovaj red snima. */
    @NotNull
    @Column(name = "fund_id", nullable = false)
    private Long fundId;

    /** Datum na koji je vrednost fonda snimljena. */
    @NotNull
    @Column(name = "snapshot_date", nullable = false)
    private LocalDate snapshotDate;

    /** Ukupna vrednost fonda na {@link #snapshotDate} (RSD): likvidna sredstva + vrednost hartija. */
    @NotNull
    @Column(name = "total_value", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalValue;

    /** Profit fonda na {@link #snapshotDate} (RSD): {@code totalValue - Sigma ClientFundPosition.totalInvested}. */
    @NotNull
    @Column(name = "profit", nullable = false, precision = 19, scale = 4)
    private BigDecimal profit;
}
