package com.banka1.stock_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the InfluxDB market-data time-series store.
 *
 * @param enabled whether the InfluxDB integration should be used
 * @param url base URL of the InfluxDB HTTP API
 * @param org InfluxDB organization name
 * @param bucket InfluxDB bucket used for market-data points
 * @param token API token used to authorize write and query calls
 */
@ConfigurationProperties(prefix = "stock.influx")
public record StockInfluxProperties(
        boolean enabled,
        String url,
        String org,
        String bucket,
        String token
) {
    public StockInfluxProperties {
        url = hasText(url) ? url : "http://localhost:8086";
        org = hasText(org) ? org : "banka1";
        bucket = hasText(bucket) ? bucket : "market_data";
        token = token == null ? "" : token;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
