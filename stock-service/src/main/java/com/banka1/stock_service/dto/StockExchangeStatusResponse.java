package com.banka1.stock_service.dto;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Runtime trading status for a stock exchange.
 */
public record StockExchangeStatusResponse(
        Long id,
        String exchangeName,
        String exchangeAcronym,
        String exchangeMICCode,
        String polity,
        String timeZone,
        LocalDate localDate,
        LocalTime localTime,
        LocalTime openTime,
        LocalTime closeTime,
        LocalTime preMarketOpenTime,
        LocalTime preMarketCloseTime,
        LocalTime postMarketOpenTime,
        LocalTime postMarketCloseTime,
        boolean isActive,
        boolean workingDay,
        boolean holiday,
        boolean open,
        boolean regularMarketOpen,
        boolean testModeBypassEnabled,
        StockExchangeMarketPhase marketPhase
) {
}
