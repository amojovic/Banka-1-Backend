package com.banka1.stock_service.dto;

import java.time.LocalDate;

/**
 * Supported history windows for the listing-details endpoint.
 *
 * <p>The period is interpreted relative to the latest available trading day for the listing
 * so seeded or test data can still produce deterministic results even when the persisted
 * history does not include the current calendar day.
 */
public enum ListingDetailsPeriod {
    DAY,
    WEEK,
    MONTH,
    YEAR,
    FIVE_YEARS,
    ALL;

    /**
     * Resolves the inclusive lower bound for this period using the provided anchor date.
     *
     * @param anchorDate latest available trading day for the listing
     * @return inclusive lower bound, or {@code null} when the full history should be returned
     */
    public LocalDate resolveStartDate(LocalDate anchorDate) {
        return switch (this) {
            case DAY -> anchorDate;
            case WEEK -> anchorDate.minusDays(6);
            case MONTH -> anchorDate.minusMonths(1);
            case YEAR -> anchorDate.minusYears(1);
            case FIVE_YEARS -> anchorDate.minusYears(5);
            case ALL -> null;
        };
    }
}
