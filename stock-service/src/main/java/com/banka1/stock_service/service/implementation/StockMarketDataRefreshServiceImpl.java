package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.client.AlphaVantageClient;
import com.banka1.stock_service.config.StockMarketDataProperties;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.dto.AlphaVantageCompanyOverviewResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyResponse;
import com.banka1.stock_service.dto.AlphaVantageDailyValue;
import com.banka1.stock_service.dto.AlphaVantageQuoteResponse;
import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;
import com.banka1.stock_service.repository.ListingDailyPriceInfoRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.service.StockMarketDataRefreshService;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Default implementation of the stock market-data refresh use case.
 *
 * <p>The service orchestrates three Alpha Vantage endpoints for one stock ticker:
 *
 * <ol>
 *     <li>quote data refreshes the current {@link Listing} snapshot</li>
 *     <li>daily time series upserts {@link ListingDailyPriceInfo} history</li>
 *     <li>company overview refreshes selected {@link Stock} fundamentals</li>
 * </ol>
 *
 * <p>The implementation intentionally keeps the provider client free of business logic.
 * This class owns all mapping into local entities and all idempotent upsert behavior.
 */
@Service
public class StockMarketDataRefreshServiceImpl implements StockMarketDataRefreshService {

    private final StockRepository stockRepository;
    private final ListingRepository listingRepository;
    private final ListingDailyPriceInfoRepository listingDailyPriceInfoRepository;
    private final AlphaVantageClient alphaVantageClient;
    private final StockMarketDataProperties stockMarketDataProperties;
    private final Clock clock;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param stockRepository repository for stock entities
     * @param listingRepository repository for current listing snapshots
     * @param listingDailyPriceInfoRepository repository for daily listing snapshots
     * @param alphaVantageClient external provider client
     * @param stockMarketDataProperties market-data configuration properties
     */
    @Autowired
    public StockMarketDataRefreshServiceImpl(
            StockRepository stockRepository,
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            AlphaVantageClient alphaVantageClient,
            StockMarketDataProperties stockMarketDataProperties
    ) {
        this(
                stockRepository,
                listingRepository,
                listingDailyPriceInfoRepository,
                alphaVantageClient,
                stockMarketDataProperties,
                Clock.systemUTC()
        );
    }

    /**
     * Creates the service with an explicit clock for deterministic tests.
     *
     * @param stockRepository repository for stock entities
     * @param listingRepository repository for current listing snapshots
     * @param listingDailyPriceInfoRepository repository for daily listing snapshots
     * @param alphaVantageClient external provider client
     * @param stockMarketDataProperties market-data configuration properties
     * @param clock time source used for {@code lastRefresh}
     */
    StockMarketDataRefreshServiceImpl(
            StockRepository stockRepository,
            ListingRepository listingRepository,
            ListingDailyPriceInfoRepository listingDailyPriceInfoRepository,
            AlphaVantageClient alphaVantageClient,
            StockMarketDataProperties stockMarketDataProperties,
            Clock clock
    ) {
        this.stockRepository = stockRepository;
        this.listingRepository = listingRepository;
        this.listingDailyPriceInfoRepository = listingDailyPriceInfoRepository;
        this.alphaVantageClient = alphaVantageClient;
        this.stockMarketDataProperties = stockMarketDataProperties;
        this.clock = clock;
    }

    /**
     * Refreshes one locally stored stock and its associated listing data.
     *
     * <p>Lookup order:
     *
     * <ol>
     *     <li>load {@link Stock} by unique ticker</li>
     *     <li>load the stock {@link Listing} by {@code listingType=STOCK} and {@code securityId=stock.id}</li>
     *     <li>fetch quote, daily history, and company overview from Alpha Vantage</li>
     *     <li>update local entities and upsert daily rows by {@code listingId + date}</li>
     *     <li>write the current UTC timestamp into {@code listing.lastRefresh}</li>
     * </ol>
     *
     * <p>Because Alpha Vantage does not expose historical bid/ask values in the used endpoints,
     * historical daily rows store the close price in those columns, while the latest trading day
     * reuses quote bid/ask when available.
     *
     * @param ticker stock ticker to refresh
     * @return summary of the completed refresh
     */
    @Override
    @Transactional
    public StockMarketDataRefreshResponse refreshStock(String ticker) {
        String normalizedTicker = normalizeTicker(ticker);
        Stock stock = findStock(normalizedTicker);
        Listing listing = findStockListing(stock);

        AlphaVantageQuoteResponse quoteResponse = alphaVantageClient.fetchQuote(normalizedTicker);
        AlphaVantageDailyResponse dailyResponse = alphaVantageClient.fetchDaily(normalizedTicker);
        AlphaVantageCompanyOverviewResponse companyOverviewResponse = alphaVantageClient.fetchCompanyOverview(normalizedTicker);

        LocalDateTime refreshTimestamp = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        applyCompanyOverview(stock, listing, companyOverviewResponse);
        applyQuote(listing, quoteResponse, refreshTimestamp);
        int refreshedDailyEntries = upsertDailyHistory(listing, quoteResponse, dailyResponse);

        stockRepository.save(stock);
        listingRepository.save(listing);

        return new StockMarketDataRefreshResponse(
                stock.getTicker(),
                stock.getId(),
                listing.getId(),
                refreshedDailyEntries,
                refreshTimestamp
        );
    }

    private Stock findStock(String ticker) {
        return stockRepository.findByTicker(ticker)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Stock with ticker %s was not found.".formatted(ticker)
                ));
    }

    private Listing findStockListing(Stock stock) {
        return listingRepository.findByListingTypeAndSecurityId(ListingType.STOCK, stock.getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing for stock ticker %s was not found.".formatted(stock.getTicker())
                ));
    }

    private void applyCompanyOverview(
            Stock stock,
            Listing listing,
            AlphaVantageCompanyOverviewResponse companyOverviewResponse
    ) {
        if (companyOverviewResponse.name() != null) {
            stock.setName(companyOverviewResponse.name());
            listing.setName(companyOverviewResponse.name());
        }
        if (companyOverviewResponse.sharesOutstanding() != null) {
            stock.setOutstandingShares(companyOverviewResponse.sharesOutstanding());
        }
        if (companyOverviewResponse.dividendYield() != null) {
            stock.setDividendYield(companyOverviewResponse.dividendYield());
        }
    }

    private void applyQuote(Listing listing, AlphaVantageQuoteResponse quoteResponse, LocalDateTime refreshTimestamp) {
        listing.setTicker(quoteResponse.symbol());
        listing.setPrice(quoteResponse.price());
        listing.setAsk(quoteResponse.ask());
        listing.setBid(quoteResponse.bid());
        listing.setChange(quoteResponse.change());
        listing.setVolume(quoteResponse.volume());
        listing.setLastRefresh(refreshTimestamp);
    }

    private int upsertDailyHistory(
            Listing listing,
            AlphaVantageQuoteResponse quoteResponse,
            AlphaVantageDailyResponse dailyResponse
    ) {
        Map<LocalDate, ListingDailyPriceInfo> existingByDate = listingDailyPriceInfoRepository
                .findAllByListingIdOrderByDateAsc(listing.getId())
                .stream()
                .collect(LinkedHashMap::new, (map, entity) -> map.put(entity.getDate(), entity), Map::putAll);

        List<AlphaVantageDailyValue> limitedHistory = dailyResponse.values()
                .stream()
                .sorted(Comparator.comparing(AlphaVantageDailyValue::date).reversed())
                .limit(stockMarketDataProperties.dailyHistoryLimit())
                .toList();

        List<ListingDailyPriceInfo> entriesToPersist = new ArrayList<>();
        for (int index = 0; index < limitedHistory.size(); index++) {
            AlphaVantageDailyValue currentValue = limitedHistory.get(index);
            AlphaVantageDailyValue previousValue = index + 1 < limitedHistory.size() ? limitedHistory.get(index + 1) : null;
            BigDecimal change = previousValue == null
                    ? BigDecimal.ZERO
                    : currentValue.closePrice().subtract(previousValue.closePrice());

            BigDecimal ask = currentValue.date().equals(quoteResponse.latestTradingDay())
                    ? quoteResponse.ask()
                    : currentValue.closePrice();
            BigDecimal bid = currentValue.date().equals(quoteResponse.latestTradingDay())
                    ? quoteResponse.bid()
                    : currentValue.closePrice();
            Long volume = currentValue.date().equals(quoteResponse.latestTradingDay())
                    ? quoteResponse.volume()
                    : currentValue.volume();

            ListingDailyPriceInfo entry = existingByDate.getOrDefault(
                    currentValue.date(),
                    createDailyEntry(listing, currentValue.date())
            );
            entry.setPrice(currentValue.closePrice());
            entry.setAsk(ask);
            entry.setBid(bid);
            entry.setChange(change);
            entry.setVolume(volume);
            entriesToPersist.add(entry);
            existingByDate.put(currentValue.date(), entry);
        }

        ListingDailyPriceInfo latestQuoteEntry = existingByDate.getOrDefault(
                quoteResponse.latestTradingDay(),
                createDailyEntry(listing, quoteResponse.latestTradingDay())
        );
        latestQuoteEntry.setPrice(quoteResponse.price());
        latestQuoteEntry.setAsk(quoteResponse.ask());
        latestQuoteEntry.setBid(quoteResponse.bid());
        latestQuoteEntry.setChange(quoteResponse.change());
        latestQuoteEntry.setVolume(quoteResponse.volume());
        entriesToPersist.removeIf(entry -> entry.getDate().equals(quoteResponse.latestTradingDay()));
        entriesToPersist.add(latestQuoteEntry);

        listingDailyPriceInfoRepository.saveAll(entriesToPersist);
        return entriesToPersist.size();
    }

    private ListingDailyPriceInfo createDailyEntry(Listing listing, LocalDate date) {
        ListingDailyPriceInfo entry = new ListingDailyPriceInfo();
        entry.setListing(listing);
        entry.setDate(date);
        return entry;
    }

    private String normalizeTicker(String ticker) {
        if (ticker == null || ticker.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Ticker must not be blank.");
        }
        return ticker.trim().toUpperCase(Locale.ROOT);
    }
}
