package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockTest {

    @Test
    void shouldReturnFixedContractSizeAndDerivedValuesFromPrice() {
        Stock stock = new Stock();
        stock.setOutstandingShares(1_000_000L);
        BigDecimal price = new BigDecimal("123.45");

        assertEquals(1, stock.getContractSize());
        assertEquals(new BigDecimal("61.7250"), stock.calculateMaintenanceMargin(price));
        assertEquals(new BigDecimal("123450000.00"), stock.calculateMarketCap(price));
    }

    @Test
    void shouldRejectNullPriceWhenCalculatingDerivedValues() {
        Stock stock = new Stock();
        stock.setOutstandingShares(500L);

        assertThrows(NullPointerException.class, () -> stock.calculateMaintenanceMargin(null));
        assertThrows(NullPointerException.class, () -> stock.calculateMarketCap(null));
    }
}
