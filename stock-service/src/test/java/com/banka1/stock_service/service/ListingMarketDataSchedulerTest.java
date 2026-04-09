package com.banka1.stock_service.service;

import com.banka1.stock_service.config.ListingRefreshProperties;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.ListingRefreshBatchResponse;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.dto.StockExchangeMarketPhase;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.repository.ListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ListingMarketDataScheduler}.
 */
@ExtendWith(MockitoExtension.class)
class ListingMarketDataSchedulerTest {

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private StockExchangeService stockExchangeService;

    @Mock
    private ListingMarketDataRefreshService listingMarketDataRefreshService;

    @Test
    void refreshOpenListingsRefreshesOnlyOpenSupportedListings() {
        Listing openStock = createListing(1L, ListingType.STOCK, 100L, "AAPL");
        Listing openForex = createListing(2L, ListingType.FOREX, 200L, "EUR/USD");
        Listing closedStock = createListing(3L, ListingType.STOCK, 200L, "MSFT");
        Listing openFutures = createListing(4L, ListingType.FUTURES, 100L, "CRUDEOILENERGY");
        Listing failingStock = createListing(5L, ListingType.STOCK, 100L, "GOOGL");

        when(listingRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(openStock, openForex, closedStock, openFutures, failingStock));
        when(stockExchangeService.getStockExchangeStatus(100L)).thenReturn(openStatus(100L));
        when(stockExchangeService.getStockExchangeStatus(200L)).thenReturn(closedStatus(200L));
        when(listingMarketDataRefreshService.refreshListing(1L)).thenReturn(new ListingRefreshResponse(
                1L,
                "AAPL",
                ListingType.STOCK,
                LocalDate.of(2026, 4, 8),
                LocalDateTime.of(2026, 4, 8, 10, 0)
        ));
        when(listingMarketDataRefreshService.refreshListing(2L)).thenReturn(new ListingRefreshResponse(
                2L,
                "EUR/USD",
                ListingType.FOREX,
                LocalDate.of(2026, 4, 8),
                LocalDateTime.of(2026, 4, 8, 10, 0)
        ));
        when(listingMarketDataRefreshService.refreshListing(5L))
                .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "provider error"));

        ListingRefreshBatchResponse response = scheduler().refreshOpenListings();

        assertThat(response.source()).isEqualTo("scheduled-listing-refresh");
        assertThat(response.processedListings()).isEqualTo(5);
        assertThat(response.refreshedCount()).isEqualTo(2);
        assertThat(response.skippedClosedCount()).isEqualTo(1);
        assertThat(response.skippedUnsupportedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);

        verify(stockExchangeService, times(1)).getStockExchangeStatus(100L);
        verify(stockExchangeService, times(1)).getStockExchangeStatus(200L);
        verify(listingMarketDataRefreshService).refreshListing(1L);
        verify(listingMarketDataRefreshService).refreshListing(2L);
        verify(listingMarketDataRefreshService).refreshListing(5L);
    }

    @Test
    void refreshOpenListingsRefreshesForexEvenWhenAssignedExchangeIsClosed() {
        Listing forexListing = createListing(2L, ListingType.FOREX, 200L, "EUR/USD");

        when(listingRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(forexListing));
        when(listingMarketDataRefreshService.refreshListing(2L)).thenReturn(new ListingRefreshResponse(
                2L,
                "EUR/USD",
                ListingType.FOREX,
                LocalDate.of(2026, 4, 8),
                LocalDateTime.of(2026, 4, 8, 10, 0)
        ));

        ListingRefreshBatchResponse response = scheduler("2026-04-08T21:00:00Z").refreshOpenListings();

        assertThat(response.processedListings()).isEqualTo(1);
        assertThat(response.refreshedCount()).isEqualTo(1);
        assertThat(response.skippedClosedCount()).isZero();
        verify(stockExchangeService, never()).getStockExchangeStatus(200L);
        verify(listingMarketDataRefreshService).refreshListing(2L);
    }

    @Test
    void refreshOpenListingsSkipsForexOnUtcWeekend() {
        Listing forexListing = createListing(2L, ListingType.FOREX, 100L, "EUR/USD");

        when(listingRepository.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "id")))
                .thenReturn(List.of(forexListing));

        ListingRefreshBatchResponse response = scheduler("2026-04-11T10:00:00Z").refreshOpenListings();

        assertThat(response.processedListings()).isEqualTo(1);
        assertThat(response.refreshedCount()).isZero();
        assertThat(response.skippedClosedCount()).isEqualTo(1);
        verify(stockExchangeService, never()).getStockExchangeStatus(100L);
        verify(listingMarketDataRefreshService, never()).refreshListing(2L);
    }

    private ListingMarketDataScheduler scheduler() {
        return new ListingMarketDataScheduler(
                listingRepository,
                stockExchangeService,
                listingMarketDataRefreshService,
                new ListingRefreshProperties(true, 900_000L),
                Clock.fixed(Instant.parse("2026-04-08T10:00:00Z"), ZoneOffset.UTC)
        );
    }

    private ListingMarketDataScheduler scheduler(String instant) {
        return new ListingMarketDataScheduler(
                listingRepository,
                stockExchangeService,
                listingMarketDataRefreshService,
                new ListingRefreshProperties(true, 900_000L),
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)
        );
    }

    private Listing createListing(Long id, ListingType listingType, Long exchangeId, String ticker) {
        StockExchange exchange = new StockExchange();
        exchange.setId(exchangeId);

        Listing listing = new Listing();
        listing.setId(id);
        listing.setListingType(listingType);
        listing.setStockExchange(exchange);
        listing.setTicker(ticker);
        listing.setPrice(BigDecimal.ONE);
        listing.setAsk(BigDecimal.ONE);
        listing.setBid(BigDecimal.ONE);
        listing.setChange(BigDecimal.ZERO);
        listing.setVolume(1L);
        listing.setLastRefresh(LocalDateTime.of(2026, 4, 8, 10, 0));
        return listing;
    }

    private StockExchangeStatusResponse openStatus(Long exchangeId) {
        return new StockExchangeStatusResponse(
                exchangeId,
                "Nasdaq",
                "NASDAQ",
                "XNAS",
                "United States",
                "America/New_York",
                LocalDate.of(2026, 4, 8),
                LocalTime.of(10, 0),
                LocalTime.of(9, 30),
                LocalTime.of(16, 0),
                null,
                null,
                null,
                null,
                true,
                true,
                false,
                true,
                true,
                false,
                StockExchangeMarketPhase.REGULAR_MARKET
        );
    }

    private StockExchangeStatusResponse closedStatus(Long exchangeId) {
        return new StockExchangeStatusResponse(
                exchangeId,
                "Nasdaq",
                "NASDAQ",
                "XNAS",
                "United States",
                "America/New_York",
                LocalDate.of(2026, 4, 8),
                LocalTime.of(21, 0),
                LocalTime.of(9, 30),
                LocalTime.of(16, 0),
                null,
                null,
                null,
                null,
                true,
                true,
                false,
                false,
                false,
                false,
                StockExchangeMarketPhase.CLOSED
        );
    }
}
