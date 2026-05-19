package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Watchlist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for persisting and querying {@link Watchlist} entities.
 */
public interface WatchlistRepository extends JpaRepository<Watchlist, Long> {

    /**
     * Returns all watchlists owned by one user, newest first.
     *
     * @param userId owner identifier from the JWT {@code id} claim
     * @return watchlists belonging to the user ordered by creation time descending
     */
    List<Watchlist> findByUserIdOrderByCreatedAtDesc(Long userId);
}
