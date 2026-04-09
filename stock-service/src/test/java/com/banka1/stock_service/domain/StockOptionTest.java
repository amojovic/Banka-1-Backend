package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockOptionTest {

    @Test
    void shouldReturnFixedContractSizeAndDerivedMaintenanceMarginFromStockPrice() {
        StockOption stockOption = new StockOption();
        BigDecimal stockPrice = new BigDecimal("212.40");

        assertEquals(100, stockOption.getContractSize());
        assertEquals(new BigDecimal("10620.0000"), stockOption.calculateMaintenanceMargin(stockPrice));
    }

    @Test
    void shouldDetermineWhetherOptionIsInTheMoney() {
        StockOption callOption = new StockOption();
        callOption.setOptionType(OptionType.CALL);
        callOption.setStrikePrice(new BigDecimal("200.0000"));

        StockOption putOption = new StockOption();
        putOption.setOptionType(OptionType.PUT);
        putOption.setStrikePrice(new BigDecimal("220.0000"));

        assertTrue(callOption.isInTheMoney(new BigDecimal("212.40")));
        assertTrue(putOption.isInTheMoney(new BigDecimal("212.40")));
        assertFalse(callOption.isInTheMoney(new BigDecimal("200.0000")));
    }

    @Test
    void shouldRejectNullStockPriceWhenCalculatingMaintenanceMargin() {
        StockOption stockOption = new StockOption();

        assertThrows(NullPointerException.class, () -> stockOption.calculateMaintenanceMargin(null));
        assertThrows(NullPointerException.class, () -> stockOption.isInTheMoney(null));
    }
}
