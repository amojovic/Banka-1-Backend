package com.banka1.stock_service.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Normalized FX exchange-rate payload returned by the Alpha Vantage client.
 *
 * <p>The external provider returns this data under the
 * {@code Realtime Currency Exchange Rate} object. The record keeps only the
 * fields needed by the service layer to upsert {@code ForexPair} and
 * {@code Listing} entities.
 *
 * @param baseCurrency base currency code, for example {@code EUR}
 * @param quoteCurrency quote currency code, for example {@code USD}
 * @param exchangeRate latest quoted exchange rate
 * @param lastRefreshed provider timestamp of the quote
 */
public record AlphaVantageForexExchangeRateResponse(
        String baseCurrency,
        String quoteCurrency,
        BigDecimal exchangeRate,
        LocalDateTime lastRefreshed
) {
}
