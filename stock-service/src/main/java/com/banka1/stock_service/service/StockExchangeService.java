package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.StockExchangeResponse;
import com.banka1.stock_service.dto.StockExchangeStatusResponse;
import com.banka1.stock_service.dto.StockExchangeToggleResponse;

import java.util.List;

/**
 * Application service for stock exchange reference data and runtime status checks.
 */
public interface StockExchangeService {

    /**
     * Returns every persisted stock exchange.
     *
     * @return list of exchanges
     */
    List<StockExchangeResponse> getStockExchanges();

    /**
     * Calculates the current trading status for a single exchange.
     *
     * @param id exchange identifier
     * @return live status response
     */
    StockExchangeStatusResponse getStockExchangeStatus(Long id);

    /**
     * Flips the active flag used for testing the open-check logic.
     *
     * @param id exchange identifier
     * @return updated active-state response
     */
    StockExchangeToggleResponse toggleStockExchangeActive(Long id);
}
