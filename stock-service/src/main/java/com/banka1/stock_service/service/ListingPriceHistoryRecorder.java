package com.banka1.stock_service.service;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.dto.ListingPricePoint;
import com.banka1.stock_service.repository.ListingPriceHistoryStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;

/**
 * Records listing price history in the time-series store after relational updates commit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingPriceHistoryRecorder {

    private static final String UNKNOWN_EXCHANGE = "UNKNOWN";

    private final ListingPriceHistoryStore listingPriceHistoryStore;

    /**
     * Schedules daily snapshots for persistence after the current transaction commits.
     *
     * @param listing listing metadata shared by the snapshots
     * @param snapshots daily snapshots to record
     */
    public void recordAfterCommit(Listing listing, Collection<ListingDailyPriceInfo> snapshots) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }

        List<ListingPricePoint> points = snapshots.stream()
                .map(snapshot -> toPoint(listing, snapshot))
                .toList();

        Runnable writeTask = () -> listingPriceHistoryStore.saveDailySnapshots(points);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    writeSafely(writeTask);
                }
            });
        } else {
            writeSafely(writeTask);
        }
    }

    private void writeSafely(Runnable writeTask) {
        try {
            writeTask.run();
        } catch (RuntimeException exception) {
            log.warn("Failed to record listing price history in InfluxDB: {}", exception.getMessage());
        }
    }

    private ListingPricePoint toPoint(Listing listing, ListingDailyPriceInfo snapshot) {
        return new ListingPricePoint(
                listing.getId(),
                listing.getTicker(),
                listing.getListingType(),
                resolveExchangeCode(listing.getStockExchange()),
                snapshot.getDate(),
                snapshot.getPrice(),
                snapshot.getAsk(),
                snapshot.getBid(),
                snapshot.getChange(),
                snapshot.getVolume()
        );
    }

    private String resolveExchangeCode(StockExchange stockExchange) {
        if (stockExchange == null) {
            return UNKNOWN_EXCHANGE;
        }
        if (hasText(stockExchange.getExchangeMICCode())) {
            return stockExchange.getExchangeMICCode();
        }
        if (hasText(stockExchange.getExchangeAcronym())) {
            return stockExchange.getExchangeAcronym();
        }
        return UNKNOWN_EXCHANGE;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
