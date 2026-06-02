package com.banka1.stock_service.scheduler;

import com.banka1.stock_service.config.ListingRefreshProperties;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingRefreshBatchResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import com.banka1.stock_service.service.PriceAlertEvaluationService;
import com.banka1.stock_service.service.StockExchangeService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled batch refresher for persisted listing snapshots.
 *
 * <p>After each refresh cycle, active price alerts are evaluated against the
 * freshly updated prices (Celina 3.2). A failure in alert evaluation does not
 * abort the refresh batch.
 */
@Slf4j
@Component
public class ListingMarketDataScheduler {

    private static final String SOURCE = "scheduled-listing-refresh";

    private final ListingRepository listingRepository;
    private final StockExchangeService stockExchangeService;
    private final ListingMarketDataRefreshService listingMarketDataRefreshService;
    private final ListingRefreshProperties listingRefreshProperties;
    private final PriceAlertEvaluationService priceAlertEvaluationService;
    private final Clock clock;

    @Value("${stock.alpha-vantage.request-delay-ms:12000}")
    private long requestDelayMs;

    @Autowired
    public ListingMarketDataScheduler(
            ListingRepository listingRepository,
            StockExchangeService stockExchangeService,
            ListingMarketDataRefreshService listingMarketDataRefreshService,
            ListingRefreshProperties listingRefreshProperties,
            PriceAlertEvaluationService priceAlertEvaluationService
    ) {
        this(
                listingRepository,
                stockExchangeService,
                listingMarketDataRefreshService,
                listingRefreshProperties,
                priceAlertEvaluationService,
                Clock.systemUTC()
        );
    }

    ListingMarketDataScheduler(
            ListingRepository listingRepository,
            StockExchangeService stockExchangeService,
            ListingMarketDataRefreshService listingMarketDataRefreshService,
            ListingRefreshProperties listingRefreshProperties,
            PriceAlertEvaluationService priceAlertEvaluationService,
            Clock clock
    ) {
        this.listingRepository = listingRepository;
        this.stockExchangeService = stockExchangeService;
        this.listingMarketDataRefreshService = listingMarketDataRefreshService;
        this.listingRefreshProperties = listingRefreshProperties;
        this.priceAlertEvaluationService = priceAlertEvaluationService;
        this.clock = clock;
    }

    @PostConstruct
    public void warnFuturesStaticData() {
        log.warn(
                "Futures listings use static CSV seed data and will NOT be refreshed by the scheduler. "
                        + "Futures market data refresh is intentionally unsupported — "
                        + "update the seed CSV and re-deploy to change futures data."
        );
    }

    @Scheduled(fixedDelayString = "${stock.listing-refresh.interval-ms:900000}")
    public void runScheduledRefresh() {
        if (!listingRefreshProperties.enabled()) {
            return;
        }

        ListingRefreshBatchResponse response = refreshOpenListings();
        log.info(
                "Scheduled listing refresh completed. processedListings={}, refreshedCount={}, "
                        + "skippedClosedCount={}, skippedStaticDataCount={}, failedCount={}",
                response.processedListings(),
                response.refreshedCount(),
                response.skippedClosedCount(),
                response.skippedUnsupportedCount(),
                response.failedCount()
        );
    }

    public ListingRefreshBatchResponse refreshOpenListings() {
        List<Listing> listings = listingRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
        Map<Long, Boolean> exchangeOpenById = new HashMap<>();
        boolean forexMarketOpen = isForexMarketOpen();

        int refreshedCount = 0;
        int skippedClosedCount = 0;
        int skippedUnsupportedCount = 0;
        int failedCount = 0;

        for (Listing listing : listings) {
            if (!isRefreshWindowOpen(listing, exchangeOpenById, forexMarketOpen)) {
                skippedClosedCount++;
                continue;
            }

            if (!supportsRefresh(listing.getListingType())) {
                skippedUnsupportedCount++;
                continue;
            }

            try {
                listingMarketDataRefreshService.refreshListing(listing.getId());
                refreshedCount++;
            } catch (ResponseStatusException exception) {
                failedCount++;
                log.warn(
                        "Failed to refresh listing id={} ticker={} because {}",
                        listing.getId(),
                        listing.getTicker(),
                        exception.getReason()
                );
            }

            if (requestDelayMs > 0) {
                try {
                    Thread.sleep(requestDelayMs);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    log.warn("Scheduler throttle sleep interrupted — aborting batch.");
                    break;
                }
            }
        }

        // Celina 3.2: evaluate active price alerts after the refresh loop.
        // A failure here must not fail the refresh batch.
        try {
            priceAlertEvaluationService.evaluateActiveAlerts();
        } catch (RuntimeException exception) {
            log.warn("Price alert evaluation after refresh failed: {}", exception.getMessage());
        }

        return new ListingRefreshBatchResponse(
                SOURCE,
                listings.size(),
                refreshedCount,
                skippedClosedCount,
                skippedUnsupportedCount,
                failedCount
        );
    }

    private boolean isRefreshWindowOpen(
            Listing listing,
            Map<Long, Boolean> exchangeOpenById,
            boolean forexMarketOpen
    ) {
        return switch (listing.getListingType()) {
            case FOREX -> forexMarketOpen;
            default -> exchangeOpenById.computeIfAbsent(
                    listing.getStockExchange().getId(),
                    this::isExchangeOpen
            );
        };
    }

    private boolean isExchangeOpen(Long stockExchangeId) {
        StockExchangeStatusResponse status = stockExchangeService.getStockExchangeStatus(stockExchangeId);
        return status.open();
    }

    private boolean isForexMarketOpen() {
        DayOfWeek dayOfWeek = LocalDate.now(clock).getDayOfWeek();
        return dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY;
    }

    private boolean supportsRefresh(ListingType listingType) {
        return listingType == ListingType.STOCK || listingType == ListingType.FOREX;
    }
}
