package com.banka1.stock_service.dto;

/**
 * Trading session classification for a stock exchange at a specific local instant.
 */
public enum StockExchangeMarketPhase {
    CLOSED,
    PRE_MARKET,
    REGULAR_MARKET,
    POST_MARKET
}
