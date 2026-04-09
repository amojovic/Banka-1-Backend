package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.Liquidity;
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
class ForexPairRepositoryTest {

    @Autowired
    private ForexPairRepository forexPairRepository;

    @Test
    void shouldPersistForexPairAndLoadItByTicker() {
        ForexPair forexPair = createPair("EUR/USD", "EUR", "USD", new BigDecimal("1.08350000"), Liquidity.HIGH);
        forexPairRepository.saveAndFlush(forexPair);

        ForexPair persisted = forexPairRepository.findByTicker("EUR/USD").orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals("EUR", persisted.getBaseCurrency());
        assertEquals("USD", persisted.getQuoteCurrency());
        assertEquals(new BigDecimal("1.08350000"), persisted.getExchangeRate());
        assertEquals(Liquidity.HIGH, persisted.getLiquidity());
        assertEquals(1_000, persisted.getContractSize());
    }

    @Test
    void shouldEnforceUniqueTickerConstraint() {
        forexPairRepository.saveAndFlush(
                createPair("EUR/USD", "EUR", "USD", new BigDecimal("1.08350000"), Liquidity.HIGH)
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> forexPairRepository.saveAndFlush(
                        createPair("EUR/USD", "EUR", "USD", new BigDecimal("1.09000000"), Liquidity.MEDIUM)
                )
        );
    }

    private ForexPair createPair(
            String ticker,
            String baseCurrency,
            String quoteCurrency,
            BigDecimal exchangeRate,
            Liquidity liquidity
    ) {
        ForexPair forexPair = new ForexPair();
        forexPair.setTicker(ticker);
        forexPair.setBaseCurrency(baseCurrency);
        forexPair.setQuoteCurrency(quoteCurrency);
        forexPair.setExchangeRate(exchangeRate);
        forexPair.setLiquidity(liquidity);
        return forexPair;
    }
}
