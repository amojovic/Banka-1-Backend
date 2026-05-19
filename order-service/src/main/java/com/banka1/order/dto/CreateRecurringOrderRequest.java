package com.banka1.order.dto;

import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request DTO for creating a standing (recurring) order — Celina 3.6.
 *
 * <p>The owner is taken from the caller's JWT, never from the payload.
 */
@Data
public class CreateRecurringOrderRequest {

    /** ID of the security listing to trade. Must be positive and valid in stock-service. */
    @NotNull
    @Positive
    private Long listingId;

    /** Whether each fired order is a BUY or a SELL. */
    @NotNull
    private OrderDirection direction;

    /** How {@code value} is interpreted: a share quantity ({@code BY_QUANTITY}) or a currency amount ({@code BY_AMOUNT}). */
    @NotNull
    private RecurringMode mode;

    /**
     * The recurring instruction's magnitude — a whole quantity of securities when
     * {@code mode} is {@code BY_QUANTITY}, or an amount of currency to spend when
     * {@code mode} is {@code BY_AMOUNT}. Must be positive.
     */
    @NotNull
    @Positive
    private BigDecimal value;

    /** ID of the bank account each fired order settles against. Must be positive. */
    @NotNull
    @Positive
    private Long accountId;

    /** Interval at which the standing order fires. */
    @NotNull
    private RecurringCadence cadence;

    /** Timestamp of the first run. Must be in the future. */
    @NotNull
    @Future
    private LocalDateTime nextRun;
}
