package com.banka1.stock_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for seeding a built-in set of stock tickers on application startup.
 *
 * <p>The stock ticker seed is intentionally simpler than the CSV-based imports.
 * It exists only to ensure that the manual stock market-data refresh flow has
 * local {@code Stock} and {@code Listing} rows to work with.
 *
 * @param enabled whether the startup seeding flow should run automatically
 */
@ConfigurationProperties(prefix = "stock.ticker-seed")
public record StockTickerSeedProperties(
        boolean enabled
) {
}
