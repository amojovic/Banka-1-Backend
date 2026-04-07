package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Listing;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link Listing} entities.
 */
public interface ListingRepository extends JpaRepository<Listing, Long> {

    /**
     * Finds one listing by its ticker.
     *
     * @param ticker listing ticker
     * @return matching listing if present
     */
    Optional<Listing> findByTicker(String ticker);

    /**
     * Loads all listings quoted on one stock exchange ordered by ticker.
     *
     * @param stockExchangeId stock exchange id
     * @return listings quoted on the provided exchange
     */
    List<Listing> findAllByStockExchangeIdOrderByTickerAsc(Long stockExchangeId);
}
