package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;

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
}
