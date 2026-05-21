package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.ListingType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Immutable daily market-data point stored in the time-series database.
 *
 * @param listingId listing identifier
 * @param ticker listing ticker
 * @param listingType listing category
 * @param exchangeCode exchange MIC code or acronym used as a tag
 * @param date trading day represented by the point
 * @param price closing or reference price
 * @param ask ask price
 * @param bid bid price
 * @param change absolute daily price change
 * @param volume traded volume
 */
public record ListingPricePoint(
        Long listingId,
        String ticker,
        ListingType listingType,
        String exchangeCode,
        LocalDate date,
        BigDecimal price,
        BigDecimal ask,
        BigDecimal bid,
        BigDecimal change,
        Long volume
) {

    /**
     * Derives dollar volume as {@code volume * price}.
     *
     * @return derived dollar volume
     */
    public BigDecimal calculateDollarVolume() {
        return BigDecimal.valueOf(volume).multiply(price);
    }
}
