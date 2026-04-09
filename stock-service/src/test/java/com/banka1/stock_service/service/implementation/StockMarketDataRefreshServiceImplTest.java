package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.client.AlphaVantageClient;
import com.banka1.stock_service.config.StockMarketDataProperties;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.AlphaVantageCompanyOverviewResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyValue;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StockMarketDataRefreshServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class StockMarketDataRefreshServiceImplTest {

    @Mock
    private StockRepository stockRepository;

    @Mock
    private ListingRepository listingRepository;

    @Mock
    private ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;

    @Mock
    private AlphaVantageClient alphaVantageClient;

    @Test
    void refreshStockUpdatesStockListingAndDailySnapshots() {
        Stock stock = createStock();
        Listing listing = createListing(stock);
        ListingDailyPriceInfo existingToday = createDailyEntry(listing, LocalDate.of(2026, 4, 8));

        when(stockRepository.findByTicker("AAPL")).thenReturn(Optional.of(stock));
        when(listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, 1L))
                .thenReturn(Optional.of(listing));
        when(listingDailyPriceInfoRepository.findAllByListingIdOrderByDateAsc(10L)).thenReturn(List.of(existingToday));
        when(alphaVantageClient.fetchQuote("AAPL")).thenReturn(new AlphaVantageQuoteResponse(
                "AAPL",
                new BigDecimal("212.40000000"),
                new BigDecimal("212.45000000"),
                new BigDecimal("212.35000000"),
                new BigDecimal("4.60000000"),
                25_000L,
                LocalDate.of(2026, 4, 8)
        ));
        when(alphaVantageClient.fetchDaily("AAPL")).thenReturn(new AlphaVantageDailyResponse(
                "AAPL",
                List.of(
                        new AlphaVantageDailyValue(LocalDate.of(2026, 4, 8), new BigDecimal("212.40000000"), 24_000L),
                        new AlphaVantageDailyValue(LocalDate.of(2026, 4, 7), new BigDecimal("207.80000000"), 19_500L)
                )
        ));
        when(alphaVantageClient.fetchCompanyOverview("AAPL")).thenReturn(new AlphaVantageCompanyOverviewResponse(
                "AAPL",
                "Apple Inc.",
                15_550_061_000L,
                new BigDecimal("0.0044")
        ));

        StockMarketDataRefreshResponse response = serviceAt("2026-04-08T10:15:30Z").refreshStock("aapl");

        assertThat(response.ticker()).isEqualTo("AAPL");
        assertThat(response.stockId()).isEqualTo(1L);
        assertThat(response.listingId()).isEqualTo(10L);
        assertThat(response.refreshedDailyEntries()).isEqualTo(2);
        assertThat(response.lastRefresh()).isEqualTo(LocalDateTime.of(2026, 4, 8, 10, 15, 30));

        assertThat(stock.getName()).isEqualTo("Apple Inc.");
        assertThat(stock.getOutstandingShares()).isEqualTo(15_550_061_000L);
        assertThat(stock.getDividendYield()).isEqualByComparingTo(new BigDecimal("0.0044"));

        assertThat(listing.getPrice()).isEqualByComparingTo(new BigDecimal("212.40000000"));
        assertThat(listing.getAsk()).isEqualByComparingTo(new BigDecimal("212.45000000"));
        assertThat(listing.getBid()).isEqualByComparingTo(new BigDecimal("212.35000000"));
        assertThat(listing.getChange()).isEqualByComparingTo(new BigDecimal("4.60000000"));
        assertThat(listing.getVolume()).isEqualTo(25_000L);
        assertThat(listing.getLastRefresh()).isEqualTo(LocalDateTime.of(2026, 4, 8, 10, 15, 30));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ListingDailyPriceInfo>> captor = ArgumentCaptor.forClass(
                (Class<List<ListingDailyPriceInfo>>) (Class<?>) List.class
        );
        verify(listingDailyPriceInfoRepository).saveAll(captor.capture());

        List<ListingDailyPriceInfo> persistedEntries = captor.getValue();
        assertThat(persistedEntries).hasSize(2);
        assertThat(persistedEntries)
                .anySatisfy(entry -> {
                    assertThat(entry.getDate()).isEqualTo(LocalDate.of(2026, 4, 8));
                    assertThat(entry.getPrice()).isEqualByComparingTo(new BigDecimal("212.40000000"));
                    assertThat(entry.getAsk()).isEqualByComparingTo(new BigDecimal("212.45000000"));
                    assertThat(entry.getBid()).isEqualByComparingTo(new BigDecimal("212.35000000"));
                    assertThat(entry.getChange()).isEqualByComparingTo(new BigDecimal("4.60000000"));
                    assertThat(entry.getVolume()).isEqualTo(25_000L);
                })
                .anySatisfy(entry -> {
                    assertThat(entry.getDate()).isEqualTo(LocalDate.of(2026, 4, 7));
                    assertThat(entry.getPrice()).isEqualByComparingTo(new BigDecimal("207.80000000"));
                    assertThat(entry.getAsk()).isEqualByComparingTo(new BigDecimal("207.80000000"));
                    assertThat(entry.getBid()).isEqualByComparingTo(new BigDecimal("207.80000000"));
                    assertThat(entry.getChange()).isEqualByComparingTo(BigDecimal.ZERO);
                    assertThat(entry.getVolume()).isEqualTo(19_500L);
                });

        verify(stockRepository).save(stock);
        verify(listingRepository).save(listing);
    }

    @Test
    void refreshStockThrowsNotFoundWhenTickerDoesNotExistLocally() {
        when(stockRepository.findByTicker("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceAt("2026-04-08T10:15:30Z").refreshStock("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(alphaVantageClient, never()).fetchQuote(any());
    }

    @Test
    void refreshStockPropagatesProviderErrorsWithoutPersistingChanges() {
        Stock stock = createStock();
        Listing listing = createListing(stock);

        when(stockRepository.findByTicker("AAPL")).thenReturn(Optional.of(stock));
        when(listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, 1L))
                .thenReturn(Optional.of(listing));
        when(alphaVantageClient.fetchQuote("AAPL")).thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "timeout"));

        assertThatThrownBy(() -> serviceAt("2026-04-08T10:15:30Z").refreshStock("AAPL"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT));

        verify(stockRepository, never()).save(any());
        verify(listingRepository, never()).save(any());
        verify(listingDailyPriceInfoRepository, never()).saveAll(any());
    }

    private StockMarketDataRefreshServiceImpl serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new StockMarketDataRefreshServiceImpl(
                stockRepository,
                listingRepository,
                listingDailyPriceInfoRepository,
                alphaVantageClient,
                new StockMarketDataProperties("https://www.alphavantage.co", "demo-key", 2),
                clock
        );
    }

    private Stock createStock() {
        Stock stock = new Stock();
        stock.setId(1L);
        stock.setTicker("AAPL");
        stock.setName("Old Apple");
        stock.setOutstandingShares(1_000L);
        stock.setDividendYield(BigDecimal.ZERO);
        return stock;
    }

    private Listing createListing(Stock stock) {
        StockExchange exchange = new StockExchange();
        exchange.setId(100L);
        exchange.setExchangeName("Nasdaq");
        exchange.setExchangeAcronym("NASDAQ");
        exchange.setExchangeMICCode("XNAS");
        exchange.setPolity("United States");
        exchange.setCurrency("USD");
        exchange.setTimeZone("America/New_York");
        exchange.setOpenTime(LocalTime.of(9, 30));
        exchange.setCloseTime(LocalTime.of(16, 0));
        exchange.setIsActive(true);

        Listing listing = new Listing();
        listing.setId(10L);
        listing.setSecurityId(stock.getId());
        listing.setListingType(ListingType.STOCK);
        listing.setStockExchange(exchange);
        listing.setTicker(stock.getTicker());
        listing.setName(stock.getName());
        listing.setLastRefresh(LocalDateTime.of(2026, 4, 7, 9, 0));
        listing.setPrice(new BigDecimal("200.00000000"));
        listing.setAsk(new BigDecimal("200.00000000"));
        listing.setBid(new BigDecimal("200.00000000"));
        listing.setChange(BigDecimal.ZERO);
        listing.setVolume(10_000L);
        return listing;
    }

    private ListingDailyPriceInfo createDailyEntry(Listing listing, LocalDate date) {
        ListingDailyPriceInfo entry = new ListingDailyPriceInfo();
        entry.setId(50L);
        entry.setListing(listing);
        entry.setDate(date);
        entry.setPrice(new BigDecimal("201.00000000"));
        entry.setAsk(new BigDecimal("201.00000000"));
        entry.setBid(new BigDecimal("201.00000000"));
        entry.setChange(new BigDecimal("1.00000000"));
        entry.setVolume(15_000L);
        return entry;
    }
}
