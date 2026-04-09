package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListingDailyPriceInfoTest {

    @Test
    void shouldReturnDerivedChangePercentAndDollarVolume() {
        ListingDailyPriceInfo dailyPriceInfo = new ListingDailyPriceInfo();
        dailyPriceInfo.setPrice(new BigDecimal("105.00"));
        dailyPriceInfo.setChange(new BigDecimal("5.00"));
        dailyPriceInfo.setVolume(12_000L);

        assertEquals(new BigDecimal("5.0000"), dailyPriceInfo.calculateChangePercent());
        assertEquals(new BigDecimal("1260000.00"), dailyPriceInfo.calculateDollarVolume());
    }

    @Test
    void shouldRejectInvalidInputsNeededForDerivedCalculations() {
        ListingDailyPriceInfo dailyPriceInfo = new ListingDailyPriceInfo();

        assertThrows(NullPointerException.class, dailyPriceInfo::calculateChangePercent);
        assertThrows(NullPointerException.class, dailyPriceInfo::calculateDollarVolume);

        dailyPriceInfo.setPrice(new BigDecimal("12.00"));
        assertThrows(NullPointerException.class, dailyPriceInfo::calculateChangePercent);

        dailyPriceInfo.setChange(new BigDecimal("12.00"));
        assertThrows(ArithmeticException.class, dailyPriceInfo::calculateChangePercent);
    }
}
