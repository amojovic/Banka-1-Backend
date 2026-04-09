package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.ListingType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Response returned after manually refreshing one listing snapshot.
 *
 * <p>The response confirms which listing was refreshed and which trading date received
 * the daily snapshot upsert.
 *
 * @param listingId listing identifier
 * @param ticker listing ticker
 * @param listingType listing category
 * @param dailySnapshotDate trading date whose daily snapshot was inserted or updated
 * @param lastRefresh UTC timestamp written into the listing snapshot
 */
public record ListingRefreshResponse(
        Long listingId,
        String ticker,
        ListingType listingType,
        LocalDate dailySnapshotDate,
        LocalDateTime lastRefresh
) {
}
