package com.banka1.stock_service.dto;

import java.math.BigDecimal;

/**
 * Stock-specific attributes returned by the listing-details endpoint.
 *
 * @param outstandingShares outstanding share count
 * @param dividendYield dividend yield stored as a decimal value
 * @param contractSize stock contract size used by the trading model
 */
public record ListingStockDetailsResponse(
        Long outstandingShares,
        BigDecimal dividendYield,
        int contractSize
) {
}
