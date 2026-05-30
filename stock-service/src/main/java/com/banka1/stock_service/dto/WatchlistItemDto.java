package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.WatchlistItem;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response view of a {@link WatchlistItem} (Celina 3.4), enriched with the
 * current market data of the followed {@link Listing}.
 *
 * <p>The market-data fields ({@code ticker}, {@code name}, {@code price},
 * {@code change}, {@code volume}, {@code listingType}) are copied from the
 * {@link Listing} entity when the item is served, so the watchlist always
 * reflects the latest market snapshot.
 *
 * @param id technical identifier of the watchlist item
 * @param watchlistId identifier of the owning watchlist
 * @param listingId identifier of the followed listing
 * @param ticker ticker of the followed listing
 * @param name display name of the followed listing
 * @param price current price of the followed listing
 * @param change current absolute daily change of the followed listing
 * @param volume current traded volume of the followed listing
 * @param listingType security category of the followed listing
 * @param addedAt timestamp when the security was added to the watchlist
 */
public record WatchlistItemDto(
        Long id,
        Long watchlistId,
        Long listingId,
        String ticker,
        String name,
        BigDecimal price,
        BigDecimal change,
        Long volume,
        ListingType listingType,
        LocalDateTime addedAt
) {

    /**
     * Maps a persisted {@link WatchlistItem} together with its current
     * {@link Listing} market snapshot to the API response view.
     *
     * @param item persisted watchlist item
     * @param listing current listing snapshot of the followed security
     * @return response DTO enriched with market data
     */
    public static WatchlistItemDto from(WatchlistItem item, Listing listing) {
        return new WatchlistItemDto(
                item.getId(),
                item.getWatchlistId(),
                item.getListingId(),
                listing.getTicker(),
                listing.getName(),
                listing.getPrice(),
                listing.getChange(),
                listing.getVolume(),
                listing.getListingType(),
                item.getAddedAt()
        );
    }
}
