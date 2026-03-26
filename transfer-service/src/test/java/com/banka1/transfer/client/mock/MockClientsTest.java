package com.banka1.transfer.client.mock;

import com.banka1.transfer.dto.client.*;
import com.banka1.transfer.dto.responses.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class MockClientsTest {

    @Test
    void testMockAccountClient() {
        MockAccountClient client = new MockAccountClient();

        AccountDto details = client.getAccountDetails("123");
        assertEquals("EUR", details.currency());
        assertEquals(new BigDecimal("10000.00"), details.availableBalance());

        UpdatedBalanceResponseDto transfer = client.executeTransfer(
                new PaymentDto("1", "2", BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, 1L)
        );
        assertNotNull(transfer.senderBalance());
    }

    @Test
    void testMockClientClient() {
        MockClientClient client = new MockClientClient();
        ClientInfoResponseDto details = client.getClientDetails(1L);
        assertEquals("Petar", details.getName());
        assertEquals("petar.mock@primer.com", details.getEmail());
    }

    @Test
    void testMockExchangeClient() {
        MockExchangeClient client = new MockExchangeClient();

        // Test ista valuta
        ExchangeResponseDto same = client.calculateExchange("RSD", "RSD", BigDecimal.TEN);
        assertEquals(BigDecimal.ONE, same.rate());
        assertEquals(BigDecimal.TEN, same.toAmount());

        // Test razlicita valuta (ovde imas logiku 1.05 rate i 1% provizije)
        ExchangeResponseDto diff = client.calculateExchange("RSD", "EUR", new BigDecimal("100"));
        // 100 * 1.05 = 105. Provizija je 1% od 100 = 1. Rezultat 105 - 1 = 104.
        assertEquals(new BigDecimal("104.00"), diff.toAmount());
        assertEquals(new BigDecimal("1.05"), diff.rate());
    }

    @Test
    void testMockVerificationClient() {
        MockVerificationClient client = new MockVerificationClient();

        // Ispravan kod
        VerificationResponseDto valid = client.validateCode("sess", "123456");
        assertTrue(valid.valid());
        assertEquals("VERIFIED", valid.status());

        // Pogresan kod
        VerificationResponseDto invalid = client.validateCode("sess", "000000");
        assertFalse(invalid.valid());
        assertEquals("PENDING", invalid.status());
    }
}
