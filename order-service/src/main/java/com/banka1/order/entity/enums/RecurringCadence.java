package com.banka1.order.entity.enums;

import java.time.LocalDateTime;

public enum RecurringCadence {

    DAILY,
    WEEKLY,
    MONTHLY;

    public LocalDateTime advance(LocalDateTime from) {
        return switch (this) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
        };
    }
}
