package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.FuturesContract;
import com.banka1.stock_service.domain.Liquidity;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.FuturesContractRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockExchangeRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.service.ListingQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ListingQueryServiceImpl}.
 *
 * <p>The query service composes persisted listings with persisted underlying
 * security rows, so these tests verify filtering, derived margin calculations,
 * sorting, and pagination against the in-memory test database.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ListingQueryServiceImplTest {

    @Autowired
    private ListingQueryService listingQueryService;

    @Autowired
    private StockExchangeRepository stockExchangeRepository;

    @Autowired
    private StockRepository stockRepository;

    @Autowired
    private FuturesContractRepository futuresContractRepository;

    @Autowired
    private ForexPairRepository forexPairRepository;

    @Autowired
    private ListingRepository listingRepository;

    @Test
    void getStockListingsAppliesFiltersSortingAndPagination() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");
        StockExchange xnys = saveExchange("New York Stock Exchange", "NYSE", "XNYS");

        saveStockListing(xnas, "AAPL", "Apple Inc.", "120.00000000", "121.00000000", "119.00000000", "3.50000000", 5_000L);
        saveStockListing(xnas, "AMZN", "Amazon.com, Inc.", "150.00000000", "151.00000000", "149.00000000", "2.00000000", 7_000L);
        saveStockListing(xnys, "MSFT", "Microsoft Corporation", "300.00000000", "301.00000000", "299.00000000", "4.00000000", 9_000L);

        ListingFilterRequest filter = new ListingFilterRequest();
        filter.setExchange("XNA");
        filter.setSearch("a");
        filter.setMinPrice(new BigDecimal("100.00000000"));
        filter.setMaxBid(new BigDecimal("200.00000000"));

        Page<ListingSummaryResponse> response = listingQueryService.getStockListings(
                filter,
                0,
                1,
                ListingSortField.PRICE,
                Sort.Direction.DESC
        );

        assertThat(response.getTotalElements()).isEqualTo(2);
        assertThat(response.getTotalPages()).isEqualTo(2);
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().getFirst().ticker()).isEqualTo("AMZN");
        assertThat(response.getContent().getFirst().initialMarginCost()).isEqualByComparingTo("82.5000000000");
    }

    @Test
    void getFuturesListingsSupportsSettlementDateFilterAndMaintenanceMarginSort() {
        StockExchange xcme = saveExchange("Chicago Mercantile Exchange", "CME", "XCME");

        saveFuturesListing(
                xcme,
                "CRUDEOILENERGY",
                "Crude Oil Energy",
                1_000,
                "Barrel",
                LocalDate.of(2026, 6, 15),
                "80.00000000",
                "81.00000000",
                "79.00000000",
                4_000L
        );
        saveFuturesListing(
                xcme,
                "GOLDMETALS",
                "Gold Metals",
                100,
                "Kilogram",
                LocalDate.of(2026, 7, 15),
                "200.00000000",
                "201.00000000",
                "199.00000000",
                1_000L
        );

        ListingFilterRequest filteredRequest = new ListingFilterRequest();
        filteredRequest.setSettlementDate(LocalDate.of(2026, 6, 15));

        Page<ListingSummaryResponse> filteredResponse = listingQueryService.getFuturesListings(
                filteredRequest,
                0,
                20,
                ListingSortField.TICKER,
                Sort.Direction.ASC
        );

        assertThat(filteredResponse.getContent()).hasSize(1);
        assertThat(filteredResponse.getContent().getFirst().ticker()).isEqualTo("CRUDEOILENERGY");
        assertThat(filteredResponse.getContent().getFirst().settlementDate()).isEqualTo(LocalDate.of(2026, 6, 15));

        Page<ListingSummaryResponse> sortedResponse = listingQueryService.getFuturesListings(
                new ListingFilterRequest(),
                0,
                20,
                ListingSortField.MAINTENANCE_MARGIN,
                Sort.Direction.DESC
        );

        assertThat(sortedResponse.getContent()).extracting(ListingSummaryResponse::ticker)
                .containsExactly("CRUDEOILENERGY", "GOLDMETALS");
    }

    @Test
    void getForexListingsReturnsDerivedInitialMarginCost() {
        StockExchange xnas = saveExchange("Nasdaq", "NASDAQ", "XNAS");

        saveForexListing(
                xnas,
                "EUR/USD",
                "EUR / USD",
                "EUR",
                "USD",
                "1.08350000",
                "1.08370000",
                "1.08330000",
                1_000L
        );

        ListingFilterRequest filter = new ListingFilterRequest();
        filter.setSearch("eur");

        Page<ListingSummaryResponse> response = listingQueryService.getForexListings(
                filter,
                0,
                20,
                ListingSortField.PRICE,
                Sort.Direction.ASC
        );

        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getContent().getFirst().ticker()).isEqualTo("EUR/USD");
        assertThat(response.getContent().getFirst().initialMarginCost()).isEqualByComparingTo("119.1850000000");
    }

    /**
     * Persists one exchange used by the listing query tests.
     *
     * @param exchangeName exchange display name
     * @param acronym exchange acronym
     * @param micCode exchange MIC code
     * @return persisted exchange
     */
    private StockExchange saveExchange(String exchangeName, String acronym, String micCode) {
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
        return stockExchangeRepository.saveAndFlush(exchange);
    }

    /**
     * Persists one stock and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker stock ticker
     * @param name stock display name
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param change current change
     * @param volume current volume
     */
    private void saveStockListing(
            StockExchange exchange,
            String ticker,
            String name,
            String price,
            String ask,
            String bid,
            String change,
            long volume
    ) {
        Stock stock = new Stock();
        stock.setTicker(ticker);
        stock.setName(name);
        stock.setOutstandingShares(1_000_000L);
        stock.setDividendYield(new BigDecimal("0.0100"));
        stock = stockRepository.saveAndFlush(stock);

        Listing listing = createListing(exchange, ListingType.STOCK, stock.getId(), ticker, name, price, ask, bid, change, volume);
        listingRepository.saveAndFlush(listing);
    }

    /**
     * Persists one futures contract and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker futures ticker
     * @param name futures display name
     * @param contractSize contract size
     * @param contractUnit contract unit
     * @param settlementDate settlement date
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param volume current volume
     */
    private void saveFuturesListing(
            StockExchange exchange,
            String ticker,
            String name,
            int contractSize,
            String contractUnit,
            LocalDate settlementDate,
            String price,
            String ask,
            String bid,
            long volume
    ) {
        FuturesContract contract = new FuturesContract();
        contract.setTicker(ticker);
        contract.setName(name);
        contract.setContractSize(contractSize);
        contract.setContractUnit(contractUnit);
        contract.setSettlementDate(settlementDate);
        contract = futuresContractRepository.saveAndFlush(contract);

        Listing listing = createListing(exchange, ListingType.FUTURES, contract.getId(), ticker, name, price, ask, bid, "1.00000000", volume);
        listingRepository.saveAndFlush(listing);
    }

    /**
     * Persists one FX pair and its linked listing.
     *
     * @param exchange quoted exchange
     * @param ticker FX ticker
     * @param name listing display name
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     * @param rate exchange rate
     * @param ask current ask
     * @param bid current bid
     * @param volume current volume
     */
    private void saveForexListing(
            StockExchange exchange,
            String ticker,
            String name,
            String baseCurrency,
            String quoteCurrency,
            String rate,
            String ask,
            String bid,
            long volume
    ) {
        ForexPair pair = new ForexPair();
        pair.setTicker(ticker);
        pair.setBaseCurrency(baseCurrency);
        pair.setQuoteCurrency(quoteCurrency);
        pair.setExchangeRate(new BigDecimal(rate));
        pair.setLiquidity(Liquidity.HIGH);
        pair = forexPairRepository.saveAndFlush(pair);

        Listing listing = createListing(exchange, ListingType.FOREX, pair.getId(), ticker, name, rate, ask, bid, "0.00000000", volume);
        listingRepository.saveAndFlush(listing);
    }

    /**
     * Creates one listing row used by the query tests.
     *
     * @param exchange quoted exchange
     * @param listingType listing category
     * @param securityId underlying security id
     * @param ticker listing ticker
     * @param name listing display name
     * @param price current price
     * @param ask current ask
     * @param bid current bid
     * @param change current change
     * @param volume current volume
     * @return new listing entity
     */
    private Listing createListing(
            StockExchange exchange,
            ListingType listingType,
            Long securityId,
            String ticker,
            String name,
            String price,
            String ask,
            String bid,
            String change,
            long volume
    ) {
        Listing listing = new Listing();
        listing.setSecurityId(securityId);
        listing.setListingType(listingType);
        listing.setStockExchange(exchange);
        listing.setTicker(ticker);
        listing.setName(name);
        listing.setLastRefresh(LocalDateTime.of(2026, 4, 8, 12, 0));
        listing.setPrice(new BigDecimal(price));
        listing.setAsk(new BigDecimal(ask));
        listing.setBid(new BigDecimal(bid));
        listing.setChange(new BigDecimal(change));
        listing.setVolume(volume);
        return listing;
    }
}
