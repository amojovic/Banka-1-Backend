package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.WatchlistItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for persisting and querying {@link WatchlistItem} entities.
 */
public interface WatchlistItemRepository extends JpaRepository<WatchlistItem, Long> {

    /**
     * Returns all items belonging to one watchlist, newest first.
     *
     * @param watchlistId identifier of the owning watchlist
     * @return items belonging to the watchlist ordered by addition time descending
     */
    List<WatchlistItem> findByWatchlistIdOrderByAddedAtDesc(Long watchlistId);

    /**
     * Counts the items belonging to one watchlist.
     *
     * @param watchlistId identifier of the owning watchlist
     * @return number of followed securities in the watchlist
     */
    long countByWatchlistId(Long watchlistId);

    /**
     * Checks whether one listing is already followed by one watchlist.
     *
     * @param watchlistId identifier of the owning watchlist
     * @param listingId identifier of the followed listing
     * @return {@code true} when the listing is already in the watchlist
     */
    boolean existsByWatchlistIdAndListingId(Long watchlistId, Long listingId);

    /**
     * Removes every item belonging to one watchlist.
     *
     * <p>Used when a watchlist is deleted so its followed securities are removed
     * together with the parent list.
     *
     * @param watchlistId identifier of the owning watchlist
     */
    void deleteByWatchlistId(Long watchlistId);
}
