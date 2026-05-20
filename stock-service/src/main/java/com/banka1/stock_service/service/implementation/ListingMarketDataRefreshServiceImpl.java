package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.client.AlphaVantageClient;
import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.dto.AlphaVantageForexExchangeRateResponse;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import com.banka1.stock_service.service.ListingPriceHistoryRecorder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;

/**
 * Default implementation of manual listing market-data refresh.
 *
 * <p>The implementation intentionally keeps the scope simpler than the full stock refresh flow:
 *
 * <ul>
 *     <li>stock listings use the latest quote endpoint</li>
 *     <li>FX listings use the latest exchange-rate endpoint</li>
 *     <li>futures listings are currently unsupported because no live provider mapping exists</li>
 * </ul>
 *
 * <p>Each refresh also upserts one {@link ListingDailyPriceInfo} row for the current trading date.
 * Because the row is updated on every intraday refresh, the last successful refresh of the day
 * naturally becomes the end-of-day snapshot.
 */
@Service
public class ListingMarketDataRefreshServiceImpl implements ListingMarketDataRefreshService {

    private static final BigDecimal ZERO_CHANGE = new BigDecimal("0.00000000");

    private final ListingRepository listingRepository;
    private final ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;
    private final StockRepository stockRepository;
    private final ForexPairRepository forexPairRepository;
    private final AlphaVantageClient alphaVantageClient;
    private final ListingPriceHistoryRecorder listingPriceHistoryRecorder;
    private final Clock clock;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param listingRepository repository for listing snapshots
     * @param listingDailyPriceInfoRepository repository for listing daily snapshots
     * @param stockRepository repository for stock entities
     * @param forexPairRepository repository for FX pair entities
     * @param alphaVantageClient external market-data provider client
     * @param listingPriceHistoryRecorder time-series price-history recorder
     */
    @Autowired
    public ListingMarketDataRefreshServiceImpl(
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            StockRepository stockRepository,
            ForexPairRepository forexPairRepository,
            AlphaVantageClient alphaVantageClient,
            ListingPriceHistoryRecorder listingPriceHistoryRecorder
    ) {
        this(
                listingRepository,
                listingDailyPriceInfoRepository,
                stockRepository,
                forexPairRepository,
                alphaVantageClient,
                listingPriceHistoryRecorder,
                Clock.systemUTC()
        );
    }

    /**
     * Creates the service with an explicit clock for deterministic tests.
     *
     * @param listingRepository repository for listing snapshots
     * @param listingDailyPriceInfoRepository repository for listing daily snapshots
     * @param stockRepository repository for stock entities
     * @param forexPairRepository repository for FX pair entities
     * @param alphaVantageClient external market-data provider client
     * @param listingPriceHistoryRecorder time-series price-history recorder
     * @param clock time source used for {@code listing.lastRefresh}
     */
    ListingMarketDataRefreshServiceImpl(
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            StockRepository stockRepository,
            ForexPairRepository forexPairRepository,
            AlphaVantageClient alphaVantageClient,
            ListingPriceHistoryRecorder listingPriceHistoryRecorder,
            Clock clock
    ) {
        this.listingRepository = listingRepository;
        this.listingDailyPriceInfoRepository = listingDailyPriceInfoRepository;
        this.stockRepository = stockRepository;
        this.forexPairRepository = forexPairRepository;
        this.alphaVantageClient = alphaVantageClient;
        this.listingPriceHistoryRecorder = listingPriceHistoryRecorder;
        this.clock = clock;
    }

    /**
     * Refreshes one persisted listing and upserts its current-day daily snapshot.
     *
     * @param listingId listing identifier
     * @return refresh summary
     */
    @Override
    @Transactional
    public ListingRefreshResponse refreshListing(Long listingId) {
        Listing listing = findListing(listingId);
        LocalDateTime refreshTimestamp = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate dailySnapshotDate;

        if (listing.getListingType() == ListingType.STOCK) {
            dailySnapshotDate = refreshStockListing(listing, refreshTimestamp);
        } else if (listing.getListingType() == ListingType.FOREX) {
            dailySnapshotDate = refreshForexListing(listing, refreshTimestamp);
        } else {
            // Futures use static seed data only — there is no live market-data provider
            // for futures in this service. This is an expected, intentional limitation,
            // not a malformed request, so 422 Unprocessable Entity is more accurate than 400.
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Futures market data refresh is not supported — futures use static seed data."
            );
        }

        ListingDailyPriceInfo dailySnapshot = upsertDailySnapshot(listing, dailySnapshotDate);
        listingRepository.save(listing);
        listingPriceHistoryRecorder.recordAfterCommit(listing, List.of(dailySnapshot));

        return new ListingRefreshResponse(
                listing.getId(),
                listing.getTicker(),
                listing.getListingType(),
                dailySnapshotDate,
                refreshTimestamp
        );
    }

    /**
     * Loads a listing by id or throws HTTP 404 if it does not exist.
     *
     * @param listingId listing identifier
     * @return existing listing entity
     * @throws ResponseStatusException with {@link HttpStatus#NOT_FOUND} when no listing is found
     */
    private Listing findListing(Long listingId) {
        return listingRepository.findById(listingId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing with id %d was not found.".formatted(listingId)
                ));
    }

    /**
     * Refreshes one stock listing snapshot from the latest quote provider response.
     *
     * <p>The method updates the listing with current bid/ask/price/volume from the provider
     * and returns the latest trading day from the quote response.
     *
     * @param listing stock listing to refresh
     * @param refreshTimestamp UTC timestamp of the refresh operation
     * @return latest trading day from the quote response
     * @throws ResponseStatusException when the underlying stock does not exist
     */
    private LocalDate refreshStockListing(Listing listing, LocalDateTime refreshTimestamp) {
        Stock stock = stockRepository.findById(listing.getSecurityId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Stock with id %d was not found for listing %d."
                                .formatted(listing.getSecurityId(), listing.getId())
                ));
        AlphaVantageQuoteResponse quoteResponse = alphaVantageClient.fetchQuote(stock.getTicker());

        listing.setTicker(stock.getTicker());
        listing.setPrice(quoteResponse.price());
        listing.setAsk(quoteResponse.ask());
        listing.setBid(quoteResponse.bid());
        listing.setChange(quoteResponse.change());
        listing.setVolume(quoteResponse.volume());
        listing.setLastRefresh(refreshTimestamp);

        return quoteResponse.latestTradingDay();
    }

    /**
     * Refreshes one FX listing snapshot from the latest exchange-rate provider response.
     *
     * <p>The method updates the listing and the linked FX pair with the current exchange rate
     * and derives the daily change from the previous price.
     *
     * @param listing FX listing to refresh
     * @param refreshTimestamp UTC timestamp of the refresh operation
     * @return date from the provider response's last-refreshed timestamp
     * @throws ResponseStatusException when the underlying FX pair does not exist or ticker format is invalid
     */
    private LocalDate refreshForexListing(Listing listing, LocalDateTime refreshTimestamp) {
        ForexPair forexPair = forexPairRepository.findById(listing.getSecurityId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Forex pair with id %d was not found for listing %d."
                                .formatted(listing.getSecurityId(), listing.getId())
                ));
        CurrencyPair currencyPair = parseCurrencyPair(listing.getTicker());
        AlphaVantageForexExchangeRateResponse exchangeRateResponse = alphaVantageClient.fetchExchangeRate(
                currencyPair.baseCurrency(),
                currencyPair.quoteCurrency()
        );

        BigDecimal previousPrice = listing.getPrice();
        BigDecimal currentPrice = exchangeRateResponse.exchangeRate();

        forexPair.setExchangeRate(currentPrice);
        forexPairRepository.save(forexPair);
        listing.setPrice(currentPrice);
        listing.setAsk(currentPrice);
        listing.setBid(currentPrice);
        listing.setChange(resolveForexChange(previousPrice, currentPrice));
        listing.setVolume((long) forexPair.getContractSize());
        listing.setLastRefresh(refreshTimestamp);

        return exchangeRateResponse.lastRefreshed().toLocalDate();
    }

    /**
     * Calculates the daily change for a FX listing from price movement.
     *
     * <p>Returns zero change when the previous price is null or zero, since percentage change
     * cannot be reliably calculated without a meaningful baseline.
     *
     * @param previousPrice previous FX exchange rate
     * @param currentPrice current FX exchange rate
     * @return absolute change from previous to current, or zero when previous is undefined
     */
    private BigDecimal resolveForexChange(BigDecimal previousPrice, BigDecimal currentPrice) {
        if (previousPrice == null || previousPrice.signum() == 0) {
            return ZERO_CHANGE;
        }
        return currentPrice.subtract(previousPrice);
    }

    /**
     * Upserts a daily price snapshot for one listing and date.
     *
     * <p>If a snapshot already exists for the listing and date, it is updated with the latest values.
     * Otherwise, a new snapshot is created.
     *
     * @param listing listing whose daily snapshot is being updated
     * @param date date of the daily snapshot
     */
    private ListingDailyPriceInfo upsertDailySnapshot(Listing listing, LocalDate date) {
        ListingDailyPriceInfo dailySnapshot = listingDailyPriceInfoRepository.findByListingIdAndDate(listing.getId(), date)
                .orElseGet(() -> createDailySnapshot(listing, date));

        dailySnapshot.setPrice(listing.getPrice());
        dailySnapshot.setAsk(listing.getAsk());
        dailySnapshot.setBid(listing.getBid());
        dailySnapshot.setChange(listing.getChange());
        dailySnapshot.setVolume(listing.getVolume());

        listingDailyPriceInfoRepository.save(dailySnapshot);
        return dailySnapshot;
    }

    /**
     * Creates a new daily price snapshot entity for one listing and date.
     *
     * @param listing listing for which the snapshot is created
     * @param date date of the daily snapshot
     * @return new daily snapshot with minimal initialization
     */
    private ListingDailyPriceInfo createDailySnapshot(Listing listing, LocalDate date) {
        ListingDailyPriceInfo dailySnapshot = new ListingDailyPriceInfo();
        dailySnapshot.setListing(listing);
        dailySnapshot.setDate(date);
        return dailySnapshot;
    }

    /**
     * Parses an FX ticker in BASE/QUOTE format and validates the component currencies.
     *
     * <p>The format must be exactly 3 letters, forward slash, 3 letters (e.g., {@code USD/EUR}).
     *
     * @param ticker FX ticker string in BASE/QUOTE format
     * @return parsed currency pair
     * @throws ResponseStatusException with {@link HttpStatus#BAD_REQUEST} when format is invalid
     */
    private CurrencyPair parseCurrencyPair(String ticker) {
        if (ticker == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FX listing ticker must not be null.");
        }

        String[] parts = ticker.toUpperCase(Locale.ROOT).split("/");
        if (parts.length != 2 || parts[0].length() != 3 || parts[1].length() != 3) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "FX listing ticker must use BASE/QUOTE format."
            );
        }

        return new CurrencyPair(parts[0], parts[1]);
    }

    /**
     * Parsed ordered FX pair extracted from the listing ticker.
     *
     * @param baseCurrency base currency code
     * @param quoteCurrency quote currency code
     */
    private record CurrencyPair(
            String baseCurrency,
            String quoteCurrency
    ) {
    }
}
