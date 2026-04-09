package com.banka1.stock_service.dto;

/**
 * Response returned after flipping the active flag of a stock exchange.
 */
public record StockExchangeToggleResponse(
        Long id,
        String exchangeName,
        String exchangeMICCode,
        boolean isActive
) {
}
