package com.banka1.marketservice.stock.repository;

import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;

/**
 * Stores stock price feed snapshots in a time-series backend.
 */
public interface StockPriceSnapshotHistoryStore {

    /**
     * Persists one stock price feed snapshot.
     *
     * @param snapshot stock price snapshot to persist
     */
    void saveSnapshot(StockPriceSnapshotDto snapshot);
}
