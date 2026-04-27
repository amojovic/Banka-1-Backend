package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.StockBulkRefreshAcceptedResponse;
import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;
import com.banka1.stock_service.service.StockMarketDataRefreshService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Administrative endpoints for manually refreshing stock market data from the external provider.
 */
@RestController
@RequestMapping("/admin/stocks")
@RequiredArgsConstructor
public class StockMarketDataRefreshAdminController {

    private final StockMarketDataRefreshService stockMarketDataRefreshService;

    /**
     * Triggers a 1-shot refresh for 1 stock ticker.
     *
     * @param ticker stock ticker to refresh
     * @return summary of the completed refresh operation
     */
    @Operation(summary = "Refresh stock market data by ticker")
    @PostMapping("/{ticker}/refresh-market-data")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public StockMarketDataRefreshResponse refreshStockMarketData(@PathVariable String ticker) {
        return stockMarketDataRefreshService.refreshStock(ticker);
    }

    /**
     * Triggers a lightweight refresh for all persisted stock tickers.
     *
     * <p>This bulk endpoint now mirrors the scheduled listing refresh behavior for stock listings:
     * it refreshes only the current market snapshot and current-day daily snapshot for each stock
     * listing. It intentionally does not fetch stock overview fundamentals or multi-day history.
     *
     * <p>The endpoint is fire-and-forget: it starts the background job and immediately returns
     * HTTP 202 instead of waiting for the batch to finish.
     *
     * @return accepted response confirming that the background refresh started
     */
    @PostMapping("/refresh-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<StockBulkRefreshAcceptedResponse> refreshAllStocks() {
        stockMarketDataRefreshService.triggerRefreshAllStocks();
        return ResponseEntity.accepted().body(new StockBulkRefreshAcceptedResponse(
                "STARTED",
                "Bulk stock refresh started."
        ));
    }
}
