package com.banka1.stock_service.dto;

import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Supported sort fields for listing catalog endpoints.
 */
public enum ListingSortField {

    /**
     * Sort by listing ticker.
     */
    TICKER,

    /**
     * Sort by current listing price.
     */
    PRICE,

    /**
     * Sort by current listing volume.
     */
    VOLUME,

    /**
     * Sort by derived maintenance margin.
     */
    MAINTENANCE_MARGIN;

    /**
     * Parses one API sort-field parameter into the enum value used internally.
     *
     * @param value raw query-parameter value
     * @return parsed sort field
     */
    public static ListingSortField fromParameter(String value) {
        if (value == null || value.isBlank()) {
            return TICKER;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "ticker" -> TICKER;
            case "price" -> PRICE;
            case "volume" -> VOLUME;
            case "maintenancemargin", "maintenance_margin", "maintenance-margin" -> MAINTENANCE_MARGIN;
            default -> throw new ResponseStatusException(
                    BAD_REQUEST,
                    "Unsupported sortBy value '%s'. Supported values are ticker, price, volume, maintenanceMargin."
                            .formatted(value)
            );
        };
    }
}
