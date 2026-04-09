package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Stock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StockRepositoryTest {

    @Autowired
    private StockRepository stockRepository;

    @Test
    void shouldPersistStockAndLoadItByTicker() {
        Stock stock = createStock("AAPL", "Apple Inc.", 15_550_061_000L, new BigDecimal("0.0044"));
        stockRepository.saveAndFlush(stock);

        Stock persisted = stockRepository.findByTicker("AAPL").orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals("Apple Inc.", persisted.getName());
        assertEquals(15_550_061_000L, persisted.getOutstandingShares());
        assertEquals(new BigDecimal("0.0044"), persisted.getDividendYield());
    }

    @Test
    void shouldEnforceUniqueTickerConstraint() {
        stockRepository.saveAndFlush(createStock("MSFT", "Microsoft Corp.", 7_433_000_000L, new BigDecimal("0.0068")));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> stockRepository.saveAndFlush(createStock("MSFT", "Duplicate Microsoft", 1_000L, new BigDecimal("0.0100")))
        );
    }

    private Stock createStock(String ticker, String name, long outstandingShares, BigDecimal dividendYield) {
        Stock stock = new Stock();
        stock.setTicker(ticker);
        stock.setName(name);
        stock.setOutstandingShares(outstandingShares);
        stock.setDividendYield(dividendYield);
        return stock;
    }
}
