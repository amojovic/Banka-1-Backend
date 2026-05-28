package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.PriceAlertCondition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Request body for creating a price alert (Celina 3.2).
 *
 * @param listingId identifier of the listing to track
 * @param condition trigger condition
 * @param threshold numeric threshold; a positive value is required for every condition
 * @param notificationType preferred delivery channel — {@code EMAIL}, {@code PUSH},
 *                          {@code IN_APP} or {@code ALL}
 */
public record CreatePriceAlertRequest(
        @NotNull Long listingId,
        @NotNull PriceAlertCondition condition,
        @NotNull @Positive BigDecimal threshold,
        @NotBlank String notificationType
) {
}
