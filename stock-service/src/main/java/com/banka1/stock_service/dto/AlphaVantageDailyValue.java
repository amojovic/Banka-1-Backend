package com.banka1.stock_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal DTO representing one normalized daily market snapshot from Alpha Vantage.
 *
 * @param date trading day of the snapshot
 * @param closePrice closing price for the day
 * @param volume daily traded volume
 */
public record AlphaVantageDailyValue(
        LocalDate date,
        BigDecimal closePrice,
        Long volume
) {
}
