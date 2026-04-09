package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.FuturesContract;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link FuturesContract} entities.
 *
 * <p>The futures import flow uses this repository to:
 *
 * <ul>
 *     <li>look up one contract by ticker in tests and future business logic</li>
 *     <li>batch-load existing contracts for a set of CSV tickers during import</li>
 * </ul>
 */
public interface FuturesContractRepository extends JpaRepository<FuturesContract, Long> {

    /**
     * Finds one futures contract by its unique ticker.
     *
     * @param ticker futures ticker
     * @return matching contract if present
     */
    Optional<FuturesContract> findByTicker(String ticker);

    /**
     * Loads all futures contracts whose tickers are part of the provided collection.
     *
     * @param tickers futures tickers used during CSV import
     * @return matching futures contracts
     */
    List<FuturesContract> findAllByTickerIn(Collection<String> tickers);
}
