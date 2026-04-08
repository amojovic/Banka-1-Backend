package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link Listing} entities.
 */
public interface ListingRepository extends JpaRepository<Listing, Long> {

    /**
     * Finds one listing by its category and underlying security identifier.
     *
     * @param listingType listing category
     * @param securityId underlying security identifier
     * @return matching listing if present
     */
    Optional<Listing> findByListingTypeAndSecurityId(ListingType listingType, Long securityId);

    /**
     * Loads all listings of one category sorted by ticker.
     *
     * @param listingType listing category
     * @return listings of the requested category
     */
    List<Listing> findAllByListingTypeOrderByTickerAsc(ListingType listingType);

    /**
     * Loads all listings quoted on one stock exchange ordered by ticker.
     *
     * @param stockExchangeId stock exchange id
     * @return listings quoted on the provided exchange
     */
    List<Listing> findAllByStockExchangeIdOrderByTickerAsc(Long stockExchangeId);
}
