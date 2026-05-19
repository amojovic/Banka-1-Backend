package com.banka1.order.entity;

import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A standing (recurring) order — Celina 3.6 dollar-cost-averaging.
 *
 * <p>A client or actuary defines a recurring instruction that, at a fixed cadence,
 * automatically places an ordinary Market Order with predefined parameters. The typical
 * case is DCA: buy securities for a fixed amount of currency on the 1st of every month
 * regardless of price. A scheduler walks the active standing orders, materializes a
 * Market Order, and advances {@link #nextRun}. The owner may pause ({@code active=false})
 * or cancel (delete) a standing order at any time.
 *
 * <p>This entity holds only the standing instruction; each fired occurrence is a normal
 * {@link Order} created through the regular order-creation flow, so funds checks, agent
 * daily-limit reservation and async execution all apply unchanged.
 */
@Entity
@Table(name = "recurring_orders")
@Getter
@Setter
@NoArgsConstructor
public class RecurringOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID of the client or actuary who owns this standing order. */
    @Column(nullable = false)
    private Long userId;

    /** ID of the security listing in stock-service that the fired order trades. */
    @Column(nullable = false)
    private Long listingId;

    /** Whether each fired order is a BUY or a SELL. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderDirection direction;

    /** How {@link #value} is interpreted: a share quantity or a currency amount. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringMode mode;

    /**
     * The recurring instruction's magnitude — a whole quantity of securities when
     * {@link #mode} is {@code BY_QUANTITY}, or an amount of currency to spend when
     * {@link #mode} is {@code BY_AMOUNT}.
     *
     * <p>The column name is quoted because {@code value} is a reserved word in H2
     * (used by the {@code @DataJpaTest} slice) — the same treatment {@code ActuaryInfo}
     * applies to its {@code limit} column. The Liquibase changeset quotes it to match.
     */
    @Column(name = "\"value\"", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    /** ID of the bank account each fired order settles against. */
    @Column(nullable = false)
    private Long accountId;

    /** Interval at which this standing order fires. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringCadence cadence;

    /**
     * Timestamp of the next due run. The scheduler picks up standing orders whose
     * {@code nextRun} is in the past and, after each attempt, advances it by one
     * {@link #cadence} step.
     */
    @Column(nullable = false)
    private LocalDateTime nextRun;

    /**
     * Whether this standing order is active. A paused standing order ({@code false})
     * is skipped by the scheduler; resuming sets it back to {@code true}.
     */
    @Column(nullable = false)
    private Boolean active = true;

    /** Timestamp of when the standing order was created. Set once on insert. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
