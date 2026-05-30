package com.banka1.stock_service.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for adding a followed security to a watchlist (Celina 3.4).
 *
 * <p>The owning watchlist is taken from the request path; only the followed
 * listing is supplied in the body.
 *
 * @param listingId identifier of the listing to add to the watchlist
 */
public record AddWatchlistItemRequest(
        @NotNull Long listingId
) {
}
