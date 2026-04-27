package com.banka1.stock_service.dto;

/**
 * Response DTO returned when the asynchronous bulk stock refresh is accepted.
 *
 * @param status fixed accepted state for the background job trigger
 * @param message human-readable confirmation that the refresh started
 */
public record StockBulkRefreshAcceptedResponse(
        String status,
        String message
) {
}
