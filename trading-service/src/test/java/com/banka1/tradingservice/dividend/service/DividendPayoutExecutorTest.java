package com.banka1.tradingservice.dividend.service;

import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.repository.OrderRepository;
import com.banka1.tradingservice.dividend.client.DividendAccountClient;
import com.banka1.tradingservice.dividend.client.DividendAccountClient.OwnerAccount;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import com.banka1.tradingservice.dividend.domain.DividendPayout;
import com.banka1.tradingservice.dividend.repository.DividendPayoutRepository;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-14 / WP-14b: unit testovi za {@link DividendPayoutExecutor} — formula
 * obracuna, 15% porez na licne pozicije, izostanak poreza za pozicije banke,
 * zaokruzivanje, idempotency, FX konverzija, fallback lanac razresavanja
 * racuna drzaoca, i bank/personal split logika.
 */
@ExtendWith(MockitoExtension.class)
class DividendPayoutExecutorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Mock
    private DividendAccountClient accountClient;
    @Mock
    private MarketPriceClient marketPriceClient;
    @Mock
    private DividendPayoutRepository payoutRepository;
    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private DividendPayoutExecutor executor;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(executor, "taxRate", new BigDecimal("0.15"));
    }

    private DividendDataClient.DividendData stock(String currency, BigDecimal price, BigDecimal yield) {
        return new DividendDataClient.DividendData(1L, "AAPL", price, currency, yield);
    }

    private Portfolio holder(Long userId, int quantity) {
        Portfolio p = new Portfolio();
        p.setUserId(userId);
        p.setListingId(1L);
        p.setListingType(ListingType.STOCK);
        p.setQuantity(quantity);
        p.setAveragePurchasePrice(new BigDecimal("100.0000"));
        return p;
    }

    // -------------------- formula --------------------

    @Test
    void computeGross_appliesQuarterlyYieldFormula() {
        // 10 * 200 * (0.08 / 4) = 10 * 200 * 0.02 = 40.0000
        BigDecimal gross = executor.computeGross(10, new BigDecimal("200"), new BigDecimal("0.08"));
        assertEquals(0, new BigDecimal("40.0000").compareTo(gross), "gross = qty * price * yield/4");
    }

    @Test
    void computeGross_roundsToFourDecimals() {
        // 3 * 33.33 * (0.05 / 4) = 99.99 * 0.0125 = 1.249875 -> 1.2499
        BigDecimal gross = executor.computeGross(3, new BigDecimal("33.33"), new BigDecimal("0.05"));
        assertEquals(0, new BigDecimal("1.2499").compareTo(gross), "HALF_UP zaokruzivanje na 4 decimale");
    }

    @Test
    void computeGross_returnsZeroForNonPositiveOrNullInputs() {
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(0, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(null, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(5, null, new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(5, new BigDecimal("10"), null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(5, new BigDecimal("10"), BigDecimal.ZERO)));
        assertEquals(0, BigDecimal.ZERO.compareTo(
                executor.computeGross(5, BigDecimal.ZERO, new BigDecimal("0.1"))));
    }

    // -------------------- personal holder: 15% tax, RSD listing --------------------

    @Test
    void payoutForHolder_personalRsdStock_withholds15PercentTax() {
        // RSD stock — bez konverzije. gross = 100 * 50 * (0.04/4) = 100*50*0.01 = 50.0000
        // tax = 0.15 * 50 = 7.5000 RSD ; net = 50 - 7.5 = 42.5000
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals(0, new BigDecimal("50.0000").compareTo(payout.getGrossAmount()));
        assertEquals(0, new BigDecimal("7.5000").compareTo(payout.getTaxAmountRsd()));
        assertEquals(0, new BigDecimal("42.5000").compareTo(payout.getNetAmount()));
        assertFalse(payout.isForBank());
        assertEquals(701L, payout.getAccountId());

        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("42.5000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("7.5000")), eq(-2L));
        verifyNoInteractions(marketPriceClient);
        verify(accountClient, never()).defaultRsdAccountNumber(anyLong());
    }

    // -------------------- WP-14b: personal holder, USD listing, USD account --------------------

    @Test
    void payoutForHolder_personalUsdStock_holderHasUsdAccount_paysInUsdNoConversion() {
        // USD stock, drzalac IMA USD racun -> isplata u USD.
        // gross = 10 * 100 * (0.08/4) = 20.0000 USD
        // grossRsd = 20 * 117 = 2340 ; tax = 0.15 * 2340 = 351.0000 RSD
        // taxInUsd = 351 RSD -> 3.0000 USD ; netListing = 20 - 3 = 17.0000 USD
        Portfolio holder = holder(7L, 10);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "USD")).thenReturn(new OwnerAccount(801L, "USD-7"));
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));
        when(marketPriceClient.convertNoCommission(new BigDecimal("20.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("2340.0000")));
        when(marketPriceClient.convertNoCommission(new BigDecimal("351.0000"), "RSD", "USD"))
                .thenReturn(Optional.of(new BigDecimal("3.0000")));

        boolean paid = executor.payoutForHolder(
                stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals("USD", payout.getCurrency());
        assertEquals(0, new BigDecimal("20.0000").compareTo(payout.getGrossAmount()));
        assertEquals(0, new BigDecimal("351.0000").compareTo(payout.getTaxAmountRsd()));
        assertEquals(0, new BigDecimal("17.0000").compareTo(payout.getNetAmount()));
        assertEquals(801L, payout.getAccountId());

        verify(accountClient).creditAccount(eq("USD-7"), eq(new BigDecimal("17.0000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("351.0000")), eq(-2L));
        verify(accountClient, never()).accountInCurrency(7L, "RSD");
        verify(accountClient, never()).defaultRsdAccountNumber(anyLong());
    }

    // -------------------- WP-14b: personal holder, USD listing, RSD fallback --------------------

    @Test
    void payoutForHolder_personalUsdStock_noUsdAccount_fallsBackToRsdWithConversion() {
        // USD stock, drzalac NEMA USD racun -> fallback na RSD racun.
        // grossRsd = 2340 ; tax = 351.0000 RSD ; netRsd = 1989.0000 RSD
        Portfolio holder = holder(7L, 10);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "USD")).thenReturn(null);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));
        when(marketPriceClient.convertNoCommission(new BigDecimal("20.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("2340.0000")));
        when(marketPriceClient.convertNoCommission(new BigDecimal("351.0000"), "RSD", "USD"))
                .thenReturn(Optional.of(new BigDecimal("3.0000")));

        boolean paid = executor.payoutForHolder(
                stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals("USD", payout.getCurrency());
        assertEquals(0, new BigDecimal("17.0000").compareTo(payout.getNetAmount()));
        assertEquals(701L, payout.getAccountId());

        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("1989.0000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("351.0000")), eq(-2L));
    }

    // -------------------- WP-14b: legacy endpoint fallback --------------------

    @Test
    void payoutForHolder_personalRsdStock_resolvedViaLegacyEndpoint_accountIdNull() {
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(accountClient.defaultRsdAccountNumber(7L)).thenReturn("RSD-7");
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId(), "stari endpoint ne vraca id -> accountId null");
        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("42.5000")), eq(7L));
    }

    @Test
    void payoutForHolder_personalHolder_withoutAnyAccount_recordsPayoutButSkipsCredit() {
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(accountClient.defaultRsdAccountNumber(7L)).thenReturn(null);
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid, "isplata se i dalje evidentira");
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId());
        verify(accountClient, never()).creditAccount(eq("RSD-7"), any(), anyLong());
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("7.5000")), eq(-2L));
    }

    // -------------------- bank-held holder: no tax --------------------

    @Test
    void payoutForHolder_allBankHeld_noTax_fullGrossToBankProfit() {
        // Cela pozicija je bank-held: orderRepository vraca 100 za ukupnu kolicinu 100.
        // gross = 100 * 50 * (0.04/4) = 50.0000 RSD ; bez poreza
        Portfolio holder = holder(99L, 100);
        when(orderRepository.bankHeldBuyQuantity(99L, 1L)).thenReturn(100L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(99L, 1L, AS_OF, true))
                .thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK", -1L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertTrue(payout.isForBank());
        assertEquals(0, BigDecimal.ZERO.compareTo(payout.getTaxAmountRsd()), "pozicija banke = 0 poreza");
        assertEquals(0, payout.getGrossAmount().compareTo(payout.getNetAmount()), "neto = bruto za bank-held");
        assertEquals(1001L, payout.getAccountId());
        assertEquals(100, payout.getQuantity(), "kolicina = bank-held kolicina");

        verify(accountClient).creditAccount(eq("RSD-BANK"), eq(new BigDecimal("50.0000")), eq(-1L));
        verify(accountClient, never()).stateRsdAccount();
        verify(accountClient, never()).accountInCurrency(anyLong(), any());
    }

    @Test
    void payoutForHolder_bankHeld_bankAccountUnresolved_recordsPayoutButSkipsCredit() {
        Portfolio holder = holder(99L, 100);
        when(orderRepository.bankHeldBuyQuantity(99L, 1L)).thenReturn(100L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(99L, 1L, AS_OF, true))
                .thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(null);

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid, "isplata se i dalje evidentira");
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId());
        verify(accountClient, never()).creditAccount(any(), any(), anyLong());
    }

    // -------------------- bank/personal split --------------------

    @Test
    void payoutForHolder_split_bankAndPersonalPortions_twoPaidRows() {
        // 60 bank-held + 40 personal od ukupnih 100.
        // Personal gross = 40 * 50 * 0.01 = 20.0000 ; tax = 3.0000 ; net = 17.0000
        // Bank gross = 60 * 50 * 0.01 = 30.0000 ; bez poreza
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(60L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, true))
                .thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(accountClient.stateRsdAccount()).thenReturn(new OwnerAccount(900L, "RSD-STATE", -2L));
        when(accountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK", -1L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);

        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository, times(2)).save(captor.capture());

        List<DividendPayout> saved = captor.getAllValues();
        DividendPayout personal = saved.stream().filter(p -> !p.isForBank()).findFirst().orElseThrow();
        DividendPayout bank = saved.stream().filter(DividendPayout::isForBank).findFirst().orElseThrow();

        assertEquals(40, personal.getQuantity());
        assertEquals(0, new BigDecimal("20.0000").compareTo(personal.getGrossAmount()));
        assertEquals(0, new BigDecimal("3.0000").compareTo(personal.getTaxAmountRsd()),
                "15% od 20 RSD bruto = 3 RSD poreza");

        assertEquals(60, bank.getQuantity());
        assertEquals(0, new BigDecimal("30.0000").compareTo(bank.getGrossAmount()));
        assertEquals(0, BigDecimal.ZERO.compareTo(bank.getTaxAmountRsd()), "bank-held bez poreza");

        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("3.0000")), eq(-2L));
        verify(accountClient).creditAccount(eq("RSD-BANK"), eq(new BigDecimal("30.0000")), eq(-1L));
    }

    @Test
    void payoutForHolder_bankQtyClampedToTotalWhenOrdersExceedPortfolio() {
        // orderRepository vraca 200 ali portfolio ima samo 100 — clamp na 100 (sve bank-held)
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(200L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, true))
                .thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK", -1L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertTrue(payout.isForBank());
        assertEquals(100, payout.getQuantity(), "kolicina je stisnuta na max portfolija");
        // Nema licne isplate — personalQty = 100 - 100 = 0
        verify(accountClient, never()).stateRsdAccount();
    }

    // -------------------- idempotency per for_bank --------------------

    @Test
    void payoutForHolder_skipsPersonalWhenAlreadyPaidPersonal_stillPaysBankIfNew() {
        // Licna isplata vec postoji, bank-held jos nije isplacen.
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(60L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(true); // vec isplaceno
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, true))
                .thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK", -1L));

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid, "bank-held isplata prolazi i kad je licna vec gotova");
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertTrue(captor.getValue().isForBank(), "jedini sacuvani red mora biti bank-held");
    }

    @Test
    void payoutForHolder_skipsBothWhenBothAlreadyPaid_returnsFalse() {
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(60L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(true);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, true))
                .thenReturn(true);

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertFalse(paid, "obe vec isplacene -> vraca false");
        verify(payoutRepository, never()).save(any());
        verify(accountClient, never()).creditAccount(any(), any(), anyLong());
    }

    @Test
    void payoutForHolder_skipsWhenGrossIsZero() {
        Portfolio holder = holder(7L, 100);
        when(orderRepository.bankHeldBuyQuantity(7L, 1L)).thenReturn(0L);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDateAndForBank(7L, 1L, AS_OF, false))
                .thenReturn(false);

        // yield = 0 -> gross = 0
        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), BigDecimal.ZERO), holder, AS_OF);

        assertFalse(paid);
        verify(payoutRepository, never()).save(any());
    }
}
