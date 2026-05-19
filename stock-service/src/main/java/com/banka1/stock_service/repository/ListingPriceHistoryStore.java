package com.banka1.stock_service.repository;

import com.banka1.stock_service.dto.ListingPricePoint;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Repository abstraction for listing market-data history stored as a time series.
 */
public interface ListingPriceHistoryStore {

    /**
     * Persists daily market-data points.
     *
     * @param points points to persist
     */
    void saveDailySnapshots(Collection<ListingPricePoint> points);

    /**
     * Loads daily market-data points for one listing.
     *
     * @param listingId listing identifier
     * @param startDate inclusive lower date bound, or {@code null} for all history
     * @return matching points ordered by date ascending
     */
    List<ListingPricePoint> findDailySnapshots(Long listingId, LocalDate startDate);
}
