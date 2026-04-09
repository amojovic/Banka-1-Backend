package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.StockExchangeImportResponse;
import com.banka1.stock_service.service.StockExchangeCsvImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative endpoints for stock exchange reference data management.
 */
@RestController
@RequestMapping("/admin/stock-exchanges")
@RequiredArgsConstructor
public class StockExchangeAdminController {

    private final StockExchangeCsvImportService stockExchangeCsvImportService;

    /**
     * Triggers a manual import of stock exchange data from the configured CSV resource.
     *
     * @return import summary for the executed CSV load
     */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPERVISOR', 'ADMIN', 'SERVICE')")
    public StockExchangeImportResponse importStockExchanges() {
        return stockExchangeCsvImportService.importFromConfiguredCsv();
    }
}
