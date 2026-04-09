package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.ListingRefreshResponse;

/**
 * Service contract for refreshing current market data of one persisted listing.
 */
public interface ListingMarketDataRefreshService {

    /**
     * Refreshes one listing and upserts its current-day historical snapshot.
     *
     * @param listingId listing identifier
     * @return refresh summary for the updated listing
     */
    ListingRefreshResponse refreshListing(Long listingId);
}
