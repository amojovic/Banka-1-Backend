package com.banka1.stock_service.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the external stock market data provider.
 *
 * @param baseUrl base URL of the external market data API
 * @param apiKey API key used to access the provider;
 * @param dailyHistoryLimit number of recent daily snapshots persisted during one refresh
 */
@Validated
@ConfigurationProperties(prefix = "stock.market-data")
public record StockMarketDataProperties(
        @NotBlank String baseUrl,
        String apiKey,
        @Positive int dailyHistoryLimit
) {
}
