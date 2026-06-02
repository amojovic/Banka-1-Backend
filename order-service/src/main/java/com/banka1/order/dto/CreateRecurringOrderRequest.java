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

@Data
public class CreateRecurringOrderRequest {

    @NotNull
    @Positive
    private Long listingId;

    @NotNull
    private OrderDirection direction;

    @NotNull
    private RecurringMode mode;

    @NotNull
    @Positive
    private BigDecimal value;

    @NotNull
    @Positive
    private Long accountId;

    @NotNull
    private RecurringCadence cadence;

    @NotNull
    @Future
    private LocalDateTime nextRun;
}
