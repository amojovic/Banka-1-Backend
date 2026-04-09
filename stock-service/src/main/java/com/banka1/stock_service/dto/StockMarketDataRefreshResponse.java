package com.banka1.stock_service.dto;

import java.time.LocalDateTime;

/**
 * Response DTO returned after one stock market-data refresh operation completes.
 *
 * @param ticker refreshed stock ticker
 * @param stockId updated stock identifier
 * @param listingId updated listing identifier
 * @param refreshedDailyEntries number of daily snapshots upserted during the refresh
 * @param lastRefresh timestamp written to the listing snapshot
 */
public record StockMarketDataRefreshResponse(
        String ticker,
        Long stockId,
        Long listingId,
        int refreshedDailyEntries,
        LocalDateTime lastRefresh
) {
}
