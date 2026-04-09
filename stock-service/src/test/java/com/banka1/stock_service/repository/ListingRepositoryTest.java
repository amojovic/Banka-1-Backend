package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.StockExchange;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ListingRepositoryTest {

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldPersistListingAndLoadItByListingTypeAndSecurityId() {
        StockExchange exchange = stockExchangeRepository.saveAndFlush(createExchange("Nasdaq", "NASDAQ", "XNAS"));

        Listing listing = createListing(
                11L,
                ListingType.STOCK,
                exchange,
                "AAPL",
                "Apple Inc.",
                LocalDateTime.of(2026, 4, 8, 10, 15, 30),
                new BigDecimal("212.40000000"),
                new BigDecimal("212.50000000"),
                new BigDecimal("212.30000000"),
                new BigDecimal("4.60000000"),
                25_000L
        );
        listingRepository.saveAndFlush(listing);

        Listing persisted = listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, 11L).orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals(11L, persisted.getSecurityId());
        assertEquals(ListingType.STOCK, persisted.getListingType());
        assertEquals(exchange.getId(), persisted.getStockExchange().getId());
        assertEquals(new BigDecimal("212.40000000"), persisted.getPrice());
        assertEquals(new BigDecimal("4.60000000"), persisted.getChange());
        assertEquals(25_000L, persisted.getVolume());
    }

    @Test
    void shouldEnforceForeignKeyConstraintForStockExchange() {
        StockExchange missingExchangeReference = entityManager.getReference(StockExchange.class, 999_999L);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> listingRepository.saveAndFlush(
                        createListing(
                                55L,
                                ListingType.FOREX,
                                missingExchangeReference,
                                "EURUSD",
                                "Euro / US Dollar",
                                LocalDateTime.of(2026, 4, 8, 10, 20, 0),
                                new BigDecimal("1.08350000"),
                                new BigDecimal("1.08360000"),
                                new BigDecimal("1.08340000"),
                                new BigDecimal("0.00120000"),
                                50_000L
                        )
                )
        );
    }

    private Listing createListing(
            Long securityId,
            ListingType listingType,
            StockExchange stockExchange,
            String ticker,
            String name,
            LocalDateTime lastRefresh,
            BigDecimal price,
            BigDecimal ask,
            BigDecimal bid,
            BigDecimal change,
            Long volume
    ) {
        Listing listing = new Listing();
        listing.setSecurityId(securityId);
        listing.setListingType(listingType);
        listing.setStockExchange(stockExchange);
        listing.setTicker(ticker);
        listing.setName(name);
        listing.setLastRefresh(lastRefresh);
        listing.setPrice(price);
        listing.setAsk(ask);
        listing.setBid(bid);
        listing.setChange(change);
        listing.setVolume(volume);
        return listing;
    }

    private StockExchange createExchange(String exchangeName, String acronym, String micCode) {
        StockExchange exchange = new StockExchange();
        exchange.setExchangeName(exchangeName);
        exchange.setExchangeAcronym(acronym);
        exchange.setExchangeMICCode(micCode);
        exchange.setPolity("United States");
        exchange.setCurrency("USD");
        exchange.setTimeZone("America/New_York");
        exchange.setOpenTime(LocalTime.of(9, 30));
        exchange.setCloseTime(LocalTime.of(16, 0));
        exchange.setIsActive(true);
        return exchange;
    }
}
