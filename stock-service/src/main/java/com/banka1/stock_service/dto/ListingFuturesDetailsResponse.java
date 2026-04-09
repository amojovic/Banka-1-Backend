package com.banka1.stock_service.dto;

import java.time.LocalDate;

/**
 * Futures-specific attributes returned by the listing-details endpoint.
 *
 * @param contractSize contract size
 * @param contractUnit contract unit
 * @param settlementDate contract settlement date
 */
public record ListingFuturesDetailsResponse(
        Integer contractSize,
        String contractUnit,
        LocalDate settlementDate
) {
}
