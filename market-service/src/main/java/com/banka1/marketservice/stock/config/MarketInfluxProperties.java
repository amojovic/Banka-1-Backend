package com.banka1.marketservice.stock.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * InfluxDB configuration for market-service stock price time-series data.
 */
@ConfigurationProperties(prefix = "stock.influx")
public record MarketInfluxProperties(
        boolean enabled,
        String url,
        String org,
        String bucket,
        String token
) {
    public MarketInfluxProperties {
        url = hasText(url) ? url : "http://localhost:8086";
        org = hasText(org) ? org : "banka1";
        bucket = hasText(bucket) ? bucket : "market_data";
        token = token == null ? "" : token;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
