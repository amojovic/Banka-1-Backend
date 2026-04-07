package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ListingDailyPriceInfoRepositoryTest {

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void shouldPersistDailyPriceInfoAndLoadItByListingAndDate() {
        StockExchange exchange = stockExchangeRepository.saveAndFlush(createExchange("Nasdaq", "NASDAQ", "XNAS"));
        Listing listing = listingRepository.saveAndFlush(createListing(exchange));

        ListingDailyPriceInfo dailyPriceInfo = createDailyPriceInfo(
                listing,
                LocalDate.of(2026, 4, 8),
                new BigDecimal("212.40000000"),
                new BigDecimal("212.50000000"),
                new BigDecimal("212.30000000"),
                new BigDecimal("4.60000000"),
                25_000L
        );
        listingDailyPriceInfoRepository.saveAndFlush(dailyPriceInfo);

        ListingDailyPriceInfo persisted = listingDailyPriceInfoRepository.findByListingIdAndDate(
                listing.getId(),
                LocalDate.of(2026, 4, 8)
        ).orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals(listing.getId(), persisted.getListing().getId());
        assertEquals(new BigDecimal("4.60000000"), persisted.getChange());
        assertEquals(25_000L, persisted.getVolume());
    }

    @Test
    void shouldEnforceForeignKeyConstraintForListing() {
        Listing missingListingReference = entityManager.getReference(Listing.class, 999_999L);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> listingDailyPriceInfoRepository.saveAndFlush(
                        createDailyPriceInfo(
                                missingListingReference,
                                LocalDate.of(2026, 4, 8),
                                new BigDecimal("212.40000000"),
                                new BigDecimal("212.50000000"),
                                new BigDecimal("212.30000000"),
                                new BigDecimal("4.60000000"),
                                25_000L
                        )
                )
        );
    }

    private Listing createListing(StockExchange exchange) {
        Listing listing = new Listing();
        listing.setSecurityId(11L);
        listing.setListingType(ListingType.STOCK);
        listing.setStockExchange(exchange);
        listing.setTicker("AAPL");
        listing.setName("Apple Inc.");
        listing.setLastRefresh(LocalDateTime.of(2026, 4, 8, 10, 15, 30));
        listing.setPrice(new BigDecimal("212.40000000"));
        listing.setAsk(new BigDecimal("212.50000000"));
        listing.setBid(new BigDecimal("212.30000000"));
        listing.setVolume(25_000L);
        return listing;
    }

    private ListingDailyPriceInfo createDailyPriceInfo(
            Listing listing,
            LocalDate date,
            BigDecimal price,
            BigDecimal ask,
            BigDecimal bid,
            BigDecimal change,
            Long volume
    ) {
        ListingDailyPriceInfo dailyPriceInfo = new ListingDailyPriceInfo();
        dailyPriceInfo.setListing(listing);
        dailyPriceInfo.setDate(date);
        dailyPriceInfo.setPrice(price);
        dailyPriceInfo.setAsk(ask);
        dailyPriceInfo.setBid(bid);
        dailyPriceInfo.setChange(change);
        dailyPriceInfo.setVolume(volume);
        return dailyPriceInfo;
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
