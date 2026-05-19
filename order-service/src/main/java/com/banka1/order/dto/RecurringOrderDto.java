package com.banka1.order.dto;

import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO describing a standing (recurring) order — Celina 3.6.
 */
@Data
public class RecurringOrderDto {

    /** Unique standing-order identifier. */
    private Long id;

    /** ID of the client or actuary who owns this standing order. */
    private Long userId;

    /** ID of the security listing each fired order trades. */
    private Long listingId;

    /** Whether each fired order is a BUY or a SELL. */
    private OrderDirection direction;

    /** How {@code value} is interpreted: a share quantity or a currency amount. */
    private RecurringMode mode;

    /** Share quantity for {@code BY_QUANTITY}, or a currency amount for {@code BY_AMOUNT}. */
    private BigDecimal value;

    /** ID of the bank account each fired order settles against. */
    private Long accountId;

    /** Interval at which this standing order fires. */
    private RecurringCadence cadence;

    /** Timestamp of the next due run. */
    private LocalDateTime nextRun;

    /** Whether the standing order is active; a paused order is skipped by the scheduler. */
    private Boolean active;

    /** Timestamp of when the standing order was created. */
    private LocalDateTime createdAt;

    /**
     * Maps a persisted standing order to its response representation.
     *
     * @param order the persisted standing order
     * @return the populated DTO
     */
    public static RecurringOrderDto from(RecurringOrder order) {
        RecurringOrderDto dto = new RecurringOrderDto();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setListingId(order.getListingId());
        dto.setDirection(order.getDirection());
        dto.setMode(order.getMode());
        dto.setValue(order.getValue());
        dto.setAccountId(order.getAccountId());
        dto.setCadence(order.getCadence());
        dto.setNextRun(order.getNextRun());
        dto.setActive(order.getActive());
        dto.setCreatedAt(order.getCreatedAt());
        return dto;
    }
}
