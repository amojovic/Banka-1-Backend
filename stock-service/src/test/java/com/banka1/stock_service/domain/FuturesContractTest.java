package com.banka1.stock_service.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FuturesContractTest {

    @Test
    void shouldReturnDerivedMaintenanceMarginFromPriceAndContractSize() {
        FuturesContract futuresContract = new FuturesContract();
        futuresContract.setContractSize(1_000);
        BigDecimal price = new BigDecimal("82.35");

        assertEquals(new BigDecimal("8235.0000"), futuresContract.calculateMaintenanceMargin(price));
    }

    @Test
    void shouldRejectNullInputsNeededForMaintenanceMarginCalculation() {
        FuturesContract futuresContract = new FuturesContract();
        futuresContract.setContractSize(250);

        assertThrows(NullPointerException.class, () -> futuresContract.calculateMaintenanceMargin(null));

        futuresContract.setContractSize(null);
        assertThrows(
                NullPointerException.class,
                () -> futuresContract.calculateMaintenanceMargin(new BigDecimal("15.00"))
        );
    }
}
