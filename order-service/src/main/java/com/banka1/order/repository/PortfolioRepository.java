package com.banka1.order.repository;

import com.banka1.order.entity.Portfolio;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for {@link Portfolio} entities.
 * Provides lookups by userId and by the unique (userId, listingId) pair.
 */
@Repository
public interface PortfolioRepository extends JpaRepository<Portfolio, Long> {

    /**
     * Returns all positions held by the given user.
     *
     * @param userId the user's identifier
     * @return list of portfolio positions for that user
     */
    List<Portfolio> findByUserId(Long userId);

    /**
     * Finds a specific position held by a user for a given listing.
     *
     * @param userId    the user's identifier
     * @param listingId the listing's identifier in stock-service
     * @return the position if it exists
     */
    Optional<Portfolio> findByUserIdAndListingId(Long userId, Long listingId);
}
