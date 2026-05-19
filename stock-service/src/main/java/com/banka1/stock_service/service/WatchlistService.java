package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.AddWatchlistItemRequest;
import com.banka1.stock_service.dto.CreateWatchlistRequest;
import com.banka1.stock_service.dto.WatchlistDto;
import com.banka1.stock_service.dto.WatchlistItemDto;

import java.util.List;

/**
 * CRUD use cases for user-defined watchlists of followed securities (Celina 3.4).
 *
 * <p>Every operation is scoped to a single caller identified by the JWT
 * {@code id} claim. Callers can never see or mutate another user's watchlists;
 * a watchlist that the caller does not own is reported as not found so its
 * existence is not leaked.
 */
public interface WatchlistService {

    /**
     * Returns all watchlists owned by the caller, newest first.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @return the caller's watchlists, each carrying its item count
     */
    List<WatchlistDto> getWatchlistsForUser(Long userId);

    /**
     * Creates a new watchlist for the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param request watchlist definition
     * @return the created watchlist
     */
    WatchlistDto createWatchlist(Long userId, CreateWatchlistRequest request);

    /**
     * Deletes one watchlist owned by the caller together with all its items.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param watchlistId watchlist identifier
     */
    void deleteWatchlist(Long userId, Long watchlistId);

    /**
     * Returns the followed securities of one watchlist owned by the caller,
     * each enriched with the listing's current market data.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param watchlistId watchlist identifier
     * @param listingTypeFilter optional security-type filter; when {@code null}
     *                          every followed security is returned
     * @return the watchlist items, optionally filtered by security type
     */
    List<WatchlistItemDto> getItems(Long userId, Long watchlistId, ListingType listingTypeFilter);

    /**
     * Adds one followed security to a watchlist owned by the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param watchlistId watchlist identifier
     * @param request followed-security definition
     * @return the created watchlist item enriched with the listing's market data
     */
    WatchlistItemDto addItem(Long userId, Long watchlistId, AddWatchlistItemRequest request);

    /**
     * Removes one followed security from a watchlist owned by the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param watchlistId watchlist identifier
     * @param itemId watchlist item identifier
     */
    void removeItem(Long userId, Long watchlistId, Long itemId);
}
