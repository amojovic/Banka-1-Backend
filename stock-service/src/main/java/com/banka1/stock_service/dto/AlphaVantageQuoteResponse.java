package com.banka1.stock_service.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Internal DTO representing the normalized Alpha Vantage quote payload used by the refresh flow.
 *
 * @param symbol ticker symbol returned by the provider
 * @param price last traded price
 * @param ask current ask price, or {@code price} when the provider does not expose level-1 quotes
 * @param bid current bid price, or {@code price} when the provider does not expose level-1 quotes
 * @param change absolute price change versus the previous close
 * @param volume latest traded volume
 * @param latestTradingDay provider-reported trading day for the quote snapshot
 */
public record AlphaVantageQuoteResponse(
        String symbol,
        BigDecimal price,
        BigDecimal ask,
        BigDecimal bid,
        BigDecimal change,
        Long volume,
        LocalDate latestTradingDay
) {
}
