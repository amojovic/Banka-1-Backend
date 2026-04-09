package com.banka1.stock_service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for loading stock exchange reference data from a CSV file.
 *
 * @param enabled whether the startup seeding flow should run automatically
 * @param csvLocation Spring resource location of the CSV file used for import
 */
@Validated
@ConfigurationProperties(prefix = "stock.exchange-seed")
public record StockExchangeSeedProperties(
        boolean enabled,
        @NotBlank String csvLocation
) {
}
