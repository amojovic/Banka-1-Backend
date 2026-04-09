package com.banka1.stock_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Historical daily price row returned by the listing-details endpoint.
 *
 * @param date trading day represented by the row
 * @param price closing or reference price
 * @param ask ask price for the day
 * @param bid bid price for the day
 * @param change absolute daily change
 * @param changePercent derived percentage change for the day
 * @param volume traded volume for the day
 * @param dollarVolume derived dollar volume for the day
 */
public record ListingDailyPriceInfoResponse(
        LocalDate date,
        BigDecimal price,
        BigDecimal ask,
        BigDecimal bid,
        BigDecimal change,
        BigDecimal changePercent,
        Long volume,
        BigDecimal dollarVolume
) {
}
