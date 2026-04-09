package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.Liquidity;

import java.math.BigDecimal;

/**
 * FX-specific attributes returned by the listing-details endpoint.
 *
 * @param baseCurrency base currency code
 * @param quoteCurrency quote currency code
 * @param exchangeRate current exchange rate stored for the pair
 * @param liquidity liquidity classification
 * @param contractSize FX contract size used by the trading model
 */
public record ListingForexDetailsResponse(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal exchangeRate,
        Liquidity liquidity,
        int contractSize
) {
}
