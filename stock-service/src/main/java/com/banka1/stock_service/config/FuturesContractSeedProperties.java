package com.banka1.stock_service.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for loading futures contract reference data from a CSV file.
 *
 * <p>These properties mirror the stock-exchange seed configuration and allow startup seeding
 * to be enabled/disabled independently for futures contracts.
 *
 * @param enabled whether the startup seeding flow should run automatically
 * @param csvLocation Spring resource location of the CSV file used for import
 */
@Validated
@ConfigurationProperties(prefix = "stock.futures-seed")
public record FuturesContractSeedProperties(
        boolean enabled,
        @NotBlank String csvLocation
) {
}
