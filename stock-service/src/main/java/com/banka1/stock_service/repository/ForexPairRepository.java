package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.ForexPair;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link ForexPair} entities.
 *
 * <p>The FX import flow uses this repository to:
 *
 * <ul>
 *     <li>look up one pair by ticker in tests and future business logic</li>
 *     <li>batch-load existing pairs for a set of CSV tickers during import</li>
 * </ul>
 */
public interface ForexPairRepository extends JpaRepository<ForexPair, Long> {

    /**
     * Finds one FX pair by its unique ticker.
     *
     * @param ticker FX ticker
     * @return matching pair if present
     */
    Optional<ForexPair> findByTicker(String ticker);

    /**
     * Loads all FX pairs whose tickers are part of the provided collection.
     *
     * @param tickers FX tickers used during CSV import
     * @return matching FX pairs
     */
    List<ForexPair> findAllByTickerIn(Collection<String> tickers);
}
