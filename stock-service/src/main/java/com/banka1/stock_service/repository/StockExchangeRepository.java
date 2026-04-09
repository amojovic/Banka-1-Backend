package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.StockExchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link StockExchange} entities.
 */
public interface StockExchangeRepository extends JpaRepository<StockExchange, Long> {

    /**
     * Loads all exchanges sorted by display name.
     *
     * @return all persisted exchanges
     */
    List<StockExchange> findAllByOrderByExchangeNameAsc();

    /**
     * Finds a stock exchange by its MIC code.
     *
     * @param exchangeMICCode unique MIC code
     * @return matching exchange if present
     */
    Optional<StockExchange> findByExchangeMICCode(String exchangeMICCode);

    /**
     * Loads all exchanges that match the provided MIC codes.
     *
     * @param exchangeMICCodes collection of MIC codes
     * @return matching exchanges
     */
    List<StockExchange> findAllByExchangeMICCodeIn(Collection<String> exchangeMICCodes);
}
