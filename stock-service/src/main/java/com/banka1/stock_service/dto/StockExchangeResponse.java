package com.banka1.stock_service.dto;

import java.time.LocalTime;

/**
 * Public stock exchange metadata returned by the listing endpoint.
 */
public record StockExchangeResponse(
        Long id,
        String exchangeName,
        String exchangeAcronym,
        String exchangeMICCode,
        String polity,
        String currency,
        String timeZone,
        LocalTime openTime,
        LocalTime closeTime,
        LocalTime preMarketOpenTime,
        LocalTime preMarketCloseTime,
        LocalTime postMarketOpenTime,
        LocalTime postMarketCloseTime,
        boolean isActive
) {
}
