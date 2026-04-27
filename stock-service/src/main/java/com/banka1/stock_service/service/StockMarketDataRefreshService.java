package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;

import java.util.List;

/**
 * Service contract for refreshing local stock market data from an external provider.
 */
public interface StockMarketDataRefreshService {

    /**
     * Refreshes one stock ticker and its related listing snapshots from the external provider.
     *
     * @param ticker stock ticker to refresh
     * @return summary of the completed refresh operation
     */
    StockMarketDataRefreshResponse refreshStock(String ticker);

    /**
     * Starts the lightweight bulk stock refresh in the background and returns immediately.
     *
     * <p>The caller is not blocked until completion. The asynchronous job executes the same
     * lightweight market-snapshot refresh flow as the scheduled listing refresher.
     */
    void triggerRefreshAllStocks();

    /**
     * Refreshes all persisted stock tickers sequentially using the same lightweight market-snapshot
     * flow as the scheduled listing refresher.
     *
     * <p>Unlike {@link #refreshStock(String)}, this bulk operation does not request stock overview
     * fundamentals or multi-day history from the provider. It refreshes the current stock listing
     * snapshot only and upserts the current-day listing daily snapshot.
     *
     * @return one result entry per stock, in the order they were processed
     */
    List<StockMarketDataRefreshResponse> refreshAllStocks();
}
