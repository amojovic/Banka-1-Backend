package com.banka1.stock_service.dto;

/**
 * Summary returned after one scheduled batch refresh pass over persisted listings.
 *
 * <p>The scheduler uses this summary only for logging and tests.
 *
 * @param source human-readable refresh source label
 * @param processedListings number of listings examined during the pass
 * @param refreshedCount number of listings successfully refreshed
 * @param skippedClosedCount number of listings skipped because their exchange was closed
 * @param skippedUnsupportedCount number of listings skipped because their listing type is unsupported
 * @param failedCount number of listings whose refresh failed after selection
 */
public record ListingRefreshBatchResponse(
        String source,
        int processedListings,
        int refreshedCount,
        int skippedClosedCount,
        int skippedUnsupportedCount,
        int failedCount
) {
}
