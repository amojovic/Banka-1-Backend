package com.banka1.stock_service.service;

import com.banka1.stock_service.client.AlphaVantageClient;
import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.AlphaVantageForexExchangeRateResponse;
import com.banka1.stock_service.dto.ForexPairImportResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Integration tests for {@link ForexPairApiImportService}.
 *
 * <p>The service is exercised against the in-memory database so the tests cover
 * the full persistence flow for {@link ForexPair} and linked {@link Listing}
 * entities while the provider itself is mocked.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ForexPairApiImportServiceTest {

    @Autowired
    private ForexPairRepository forexPairRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @Test
    void importSupportedPairsCreatesForexPairsAndListingsForAvailableProviderPairs() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");

        AlphaVantageClient alphaVantageClient = mock(AlphaVantageClient.class);
        stubAvailablePairs(alphaVantageClient, Map.of(
                "EUR/USD", response("EUR", "USD", "1.08350000", "2026-04-08T10:15:00"),
                "USD/JPY", response("USD", "JPY", "151.25000000", "2026-04-08T10:16:00")
        ));

        ForexPairApiImportService service = createService(alphaVantageClient);

        ForexPairImportResponse importResponse = service.importSupportedPairs();

        ForexPair eurUsd = forexPairRepository.findByTicker("EUR/USD").orElseThrow();
        Listing eurUsdListing = listingRepository
                .findByListingTypeAndSecurityId(ListingType.FOREX, eurUsd.getId())
                .orElseThrow();
        ForexPair usdJpy = forexPairRepository.findByTicker("USD/JPY").orElseThrow();

        assertThat(importResponse.source()).isEqualTo("alpha-vantage:supported-forex-pairs");
        assertThat(importResponse.processedRows()).isEqualTo(2);
        assertThat(importResponse.createdCount()).isEqualTo(2);
        assertThat(importResponse.updatedCount()).isZero();
        assertThat(importResponse.unchangedCount()).isZero();

        assertThat(eurUsd.getBaseCurrency()).isEqualTo("EUR");
        assertThat(eurUsd.getQuoteCurrency()).isEqualTo("USD");
        assertThat(eurUsd.getExchangeRate()).isEqualByComparingTo("1.08350000");
        assertThat(eurUsd.getContractSize()).isEqualTo(1_000);

        assertThat(eurUsdListing.getTicker()).isEqualTo("EUR/USD");
        assertThat(eurUsdListing.getName()).isEqualTo("EUR / USD");
        assertThat(eurUsdListing.getStockExchange().getExchangeMICCode()).isEqualTo("XNAS");
        assertThat(eurUsdListing.getPrice()).isEqualByComparingTo("1.08350000");
        assertThat(eurUsdListing.getAsk()).isEqualByComparingTo("1.08350000");
        assertThat(eurUsdListing.getBid()).isEqualByComparingTo("1.08350000");
        assertThat(eurUsdListing.getChange()).isEqualByComparingTo("0.00000000");
        assertThat(eurUsdListing.getVolume()).isEqualTo(1_000L);
        assertThat(eurUsdListing.getLastRefresh()).isEqualTo(LocalDateTime.of(2026, 4, 8, 10, 15));

        assertThat(usdJpy.getTicker()).isEqualTo("USD/JPY");
        assertThat(forexPairRepository.count()).isEqualTo(2);
        assertThat(listingRepository.count()).isEqualTo(2);
    }

    @Test
    void importSupportedPairsMarksAlreadyImportedPairsAsUnchanged() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");

        AlphaVantageClient alphaVantageClient = mock(AlphaVantageClient.class);
        stubAvailablePairs(alphaVantageClient, Map.of(
                "EUR/USD", response("EUR", "USD", "1.08350000", "2026-04-08T10:15:00")
        ));

        ForexPairApiImportService service = createService(alphaVantageClient);

        service.importSupportedPairs();
        ForexPairImportResponse secondImport = service.importSupportedPairs();

        assertThat(secondImport.processedRows()).isEqualTo(1);
        assertThat(secondImport.createdCount()).isZero();
        assertThat(secondImport.updatedCount()).isZero();
        assertThat(secondImport.unchangedCount()).isEqualTo(1);
        assertThat(forexPairRepository.count()).isEqualTo(1);
        assertThat(listingRepository.count()).isEqualTo(1);
    }

    @Test
    void importSupportedPairsUpdatesExistingPairAndListingWhenRateChanges() {
        saveExchange("Nasdaq", "NASDAQ", "XNAS");

        AlphaVantageClient alphaVantageClient = mock(AlphaVantageClient.class);
        AtomicInteger eurUsdCalls = new AtomicInteger();
        when(alphaVantageClient.fetchExchangeRate(anyString(), anyString())).thenAnswer(invocation -> {
            String baseCurrency = invocation.getArgument(0, String.class);
            String quoteCurrency = invocation.getArgument(1, String.class);
            if ("EUR".equals(baseCurrency) && "USD".equals(quoteCurrency)) {
                return eurUsdCalls.incrementAndGet() == 1
                        ? response("EUR", "USD", "1.08350000", "2026-04-08T10:15:00")
                        : response("EUR", "USD", "1.09500000", "2026-04-08T11:00:00");
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Pair not available");
        });

        ForexPairApiImportService service = createService(alphaVantageClient);

        service.importSupportedPairs();
        ForexPairImportResponse secondImport = service.importSupportedPairs();

        ForexPair eurUsd = forexPairRepository.findByTicker("EUR/USD").orElseThrow();
        Listing eurUsdListing = listingRepository
                .findByListingTypeAndSecurityId(ListingType.FOREX, eurUsd.getId())
                .orElseThrow();

        assertThat(secondImport.processedRows()).isEqualTo(1);
        assertThat(secondImport.createdCount()).isZero();
        assertThat(secondImport.updatedCount()).isEqualTo(1);
        assertThat(secondImport.unchangedCount()).isZero();
        assertThat(eurUsd.getExchangeRate()).isEqualByComparingTo("1.09500000");
        assertThat(eurUsdListing.getPrice()).isEqualByComparingTo("1.09500000");
        assertThat(eurUsdListing.getLastRefresh()).isEqualTo(LocalDateTime.of(2026, 4, 8, 11, 0));
    }

    /**
     * Creates the service under test with real repositories and a mocked provider client.
     *
     * @param alphaVantageClient mocked provider client
     * @return service under test
     */
    private ForexPairApiImportService createService(AlphaVantageClient alphaVantageClient) {
        return new ForexPairApiImportService(
                alphaVantageClient,
                forexPairRepository,
                listingRepository,
                stockExchangeRepository
        );
    }

    /**
     * Configures the mocked provider so unavailable pairs are skipped by default.
     *
     * @param alphaVantageClient mocked provider client
     */
    private void stubAvailablePairs(
            AlphaVantageClient alphaVantageClient,
            Map<String, AlphaVantageForexExchangeRateResponse> availablePairs
    ) {
        when(alphaVantageClient.fetchExchangeRate(anyString(), anyString())).thenAnswer(invocation -> {
            String baseCurrency = invocation.getArgument(0, String.class);
            String quoteCurrency = invocation.getArgument(1, String.class);
            AlphaVantageForexExchangeRateResponse response = availablePairs.get(baseCurrency + "/" + quoteCurrency);
            if (response != null) {
                return response;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Pair not available");
        });
    }

    /**
     * Builds one normalized provider response for the importer tests.
     *
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     * @param exchangeRate quoted exchange rate
     * @param lastRefresh quote timestamp
     * @return normalized response
     */
    private AlphaVantageForexExchangeRateResponse response(
            String baseCurrency,
            String quoteCurrency,
            String exchangeRate,
            String lastRefresh
    ) {
        return new AlphaVantageForexExchangeRateResponse(
                baseCurrency,
                quoteCurrency,
                new BigDecimal(exchangeRate),
                LocalDateTime.parse(lastRefresh)
        );
    }

    /**
     * Persists one exchange needed by the listing import flow.
     *
     * @param exchangeName full exchange name
     * @param acronym exchange acronym
     * @param micCode exchange MIC code
     */
    private void saveExchange(String exchangeName, String acronym, String micCode) {
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
        stockExchangeRepository.saveAndFlush(exchange);
    }
}
