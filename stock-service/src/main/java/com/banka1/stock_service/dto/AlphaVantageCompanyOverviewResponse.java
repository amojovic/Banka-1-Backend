package com.banka1.stock_service.dto;

import java.math.BigDecimal;

/**
 * Internal DTO representing the subset of Alpha Vantage company-overview data
 * needed by {@code stock-service}.
 *
 * @param symbol ticker symbol returned by the provider
 * @param name company name if the provider supplied it
 * @param sharesOutstanding total outstanding shares if present
 * @param dividendYield dividend yield if present
 */
public record AlphaVantageCompanyOverviewResponse(
        String symbol,
        String name,
        Long sharesOutstanding,
        BigDecimal dividendYield
) {
}
