package com.banka1.order.entity.enums;

import java.time.LocalDateTime;

/**
 * Interval at which a {@link com.banka1.order.entity.RecurringOrder} fires.
 *
 * <p>Celina 3.6: a standing order runs at fixed intervals (the typical DCA case buys on
 * the 1st of every month). After each run — successful or skipped — the scheduler advances
 * {@code nextRun} by one cadence step.
 */
public enum RecurringCadence {

    /** Fires once per day. */
    DAILY,

    /** Fires once per week. */
    WEEKLY,

    /** Fires once per month. */
    MONTHLY;

    /**
     * Advances a run timestamp by exactly one step of this cadence.
     *
     * @param from the current {@code nextRun} timestamp
     * @return the next run timestamp one cadence step later
     */
    public LocalDateTime advance(LocalDateTime from) {
        return switch (this) {
            case DAILY -> from.plusDays(1);
            case WEEKLY -> from.plusWeeks(1);
            case MONTHLY -> from.plusMonths(1);
        };
    }
}
