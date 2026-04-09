package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Repository for persisting and querying {@link Stock} entities.
 */
public interface StockRepository extends JpaRepository<Stock, Long> {

    /**
     * Finds one stock by its unique ticker symbol.
     *
     * @param ticker ticker symbol
     * @return matching stock if present
     */
    Optional<Stock> findByTicker(String ticker);
}
