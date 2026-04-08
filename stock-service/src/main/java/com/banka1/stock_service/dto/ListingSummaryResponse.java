package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.ListingType;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response row returned by the listing catalog endpoints.
 *
 * @param listingId local listing identifier
 * @param listingType listing category
 * @param ticker listing ticker
 * @param name listing display name
 * @param exchangeMICCode exchange MIC code of the listing
 * @param price current listing price
 * @param change current absolute change
 * @param volume current volume
 * @param initialMarginCost derived initial margin cost
 * @param settlementDate futures settlement date when applicable
 */
public record ListingSummaryResponse(
        Long listingId,
        ListingType listingType,
        String ticker,
        String name,
        String exchangeMICCode,
        BigDecimal price,
        BigDecimal change,
        Long volume,
        BigDecimal initialMarginCost,
        LocalDate settlementDate
) {
}
