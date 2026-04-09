package com.banka1.stock_service.dto;

import java.util.List;

/**
 * Internal DTO representing the normalized Alpha Vantage daily time-series payload.
 *
 * @param symbol ticker symbol returned by the provider
 * @param values parsed daily snapshots before the service applies domain-specific upsert logic
 */
public record AlphaVantageDailyResponse(
        String symbol,
        List<AlphaVantageDailyValue> values
) {
}
