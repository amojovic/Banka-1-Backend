package com.banka1.stock_service.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the scheduled listing market-data refresh flow.
 *
 * <p>The scheduler can be switched on or off, and its fixed-delay interval is configurable
 * in milliseconds so local development can use a short delay while production-like
 * environments can keep a larger interval such as 15 minutes.
 *
 * @param enabled whether the scheduled listing refresh should run automatically
 * @param intervalMs fixed delay between scheduler executions in milliseconds
 */
@Validated
@ConfigurationProperties(prefix = "stock.listing-refresh")
public record ListingRefreshProperties(
        boolean enabled,
        @Positive long intervalMs
) {
}
