package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ListingTest {

    @Test
    void shouldReturnDerivedDollarVolumeAndInitialMarginCost() {
        Listing listing = new Listing();
        listing.setPrice(new BigDecimal("212.40"));
        listing.setVolume(25_000L);
        BigDecimal maintenanceMargin = new BigDecimal("10620.0000");

        assertEquals(new BigDecimal("5310000.00"), listing.calculateDollarVolume());
        assertEquals(new BigDecimal("11682.000000"), listing.calculateInitialMarginCost(maintenanceMargin));
    }

    @Test
    void shouldRejectNullInputsNeededForDerivedCalculations() {
        Listing listing = new Listing();

        assertThrows(NullPointerException.class, listing::calculateDollarVolume);

        listing.setPrice(new BigDecimal("125.50"));
        assertThrows(NullPointerException.class, listing::calculateDollarVolume);
        assertThrows(NullPointerException.class, () -> listing.calculateInitialMarginCost(null));
    }
}
