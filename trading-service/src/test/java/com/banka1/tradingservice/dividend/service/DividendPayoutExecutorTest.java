package com.banka1.tradingservice.dividend.service;

import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-14 / WP-14b: unit testovi za {@link DividendPayoutExecutor} — formula
 * obracuna, 15% porez na licne pozicije, izostanak poreza za pozicije banke,
 * zaokruzivanje, idempotency, FX konverzija, i fallback lanac razresavanja
 * racuna drzaoca (racun u valuti listinga -> RSD racun).
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
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(0, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(null, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(5, null, new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(5, new BigDecimal("10"), null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(5, new BigDecimal("10"), BigDecimal.ZERO)));
        assertEquals(0, BigDecimal.ZERO.compareTo(executor.computeGross(5, BigDecimal.ZERO, new BigDecimal("0.1"))));
    }

    // -------------------- personal holder: 15% tax, RSD listing --------------------

    @Test
    void payoutForHolder_personalRsdStock_withholds15PercentTax() {
        // RSD stock — bez konverzije. gross = 100 * 50 * (0.04/4) = 100*50*0.01 = 50.0000
        // tax = 0.15 * 50 = 7.5000 RSD ; net = 50 - 7.5 = 42.5000
        Portfolio holder = holder(7L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);
        // RSD listing -> samo jedan lookup, valuta RSD; racun ima id=701.
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(accountClient.stateRsdAccountNumber()).thenReturn("RSD-STATE");

        boolean paid = executor.payoutForHolder(stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals(0, new BigDecimal("50.0000").compareTo(payout.getGrossAmount()));
        assertEquals(0, new BigDecimal("7.5000").compareTo(payout.getTaxAmountRsd()));
        assertEquals(0, new BigDecimal("42.5000").compareTo(payout.getNetAmount()));
        assertFalse(payout.isForBank());
        assertEquals(701L, payout.getAccountId(), "accountId = razreseni RSD racun drzaoca");

        // neto na racun drzaoca, porez na drzavni racun
        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("42.5000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("7.5000")), eq(7L));
        // RSD listing -> nema FX konverzije ni dodatnog lookup-a u stranoj valuti
        verifyNoInteractions(marketPriceClient);
        verify(accountClient, never()).defaultRsdAccountNumber(anyLong());
    }

    // -------------------- WP-14b: personal holder, USD listing, USD account --------------------

    @Test
    void payoutForHolder_personalUsdStock_holderHasUsdAccount_paysInUsdNoConversion() {
        // USD stock, drzalac IMA USD racun -> isplata u USD, bez FX konverzije neta.
        // gross = 10 * 100 * (0.08/4) = 20.0000 USD
        // grossRsd = 20 * 117 = 2340 ; tax = 0.15 * 2340 = 351.0000 RSD
        // taxInUsd = 351 RSD -> 3.0000 USD ; netListing = 20 - 3 = 17.0000 USD
        Portfolio holder = holder(7L, 10);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.accountInCurrency(7L, "USD")).thenReturn(new OwnerAccount(801L, "USD-7"));
        when(accountClient.stateRsdAccountNumber()).thenReturn("RSD-STATE");
        when(marketPriceClient.convertNoCommission(new BigDecimal("20.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("2340.0000")));
        when(marketPriceClient.convertNoCommission(new BigDecimal("351.0000"), "RSD", "USD"))
                .thenReturn(Optional.of(new BigDecimal("3.0000")));

        boolean paid = executor.payoutForHolder(stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals("USD", payout.getCurrency());
        assertEquals(0, new BigDecimal("20.0000").compareTo(payout.getGrossAmount()), "gross u valuti listinga");
        assertEquals(0, new BigDecimal("351.0000").compareTo(payout.getTaxAmountRsd()), "porez u RSD");
        assertEquals(0, new BigDecimal("17.0000").compareTo(payout.getNetAmount()), "neto u valuti listinga");
        assertEquals(801L, payout.getAccountId(), "accountId = razreseni USD racun drzaoca");

        // neto se kreditira u USD na USD racun (bez konverzije), porez drzavi u RSD
        verify(accountClient).creditAccount(eq("USD-7"), eq(new BigDecimal("17.0000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("351.0000")), eq(7L));
        // USD racun nadjen -> RSD fallback se NE poziva
        verify(accountClient, never()).accountInCurrency(7L, "RSD");
        verify(accountClient, never()).defaultRsdAccountNumber(anyLong());
    }

    // -------------------- WP-14b: personal holder, USD listing, RSD fallback --------------------

    @Test
    void payoutForHolder_personalUsdStock_noUsdAccount_fallsBackToRsdWithConversion() {
        // USD stock, drzalac NEMA USD racun -> fallback na RSD racun, neto u RSD.
        // grossRsd = 2340 ; tax = 351.0000 RSD ; netRsd = 2340 - 351 = 1989.0000 RSD
        Portfolio holder = holder(7L, 10);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.accountInCurrency(7L, "USD")).thenReturn(null);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(accountClient.stateRsdAccountNumber()).thenReturn("RSD-STATE");
        when(marketPriceClient.convertNoCommission(new BigDecimal("20.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("2340.0000")));
        when(marketPriceClient.convertNoCommission(new BigDecimal("351.0000"), "RSD", "USD"))
                .thenReturn(Optional.of(new BigDecimal("3.0000")));

        boolean paid = executor.payoutForHolder(stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertEquals("USD", payout.getCurrency());
        // netAmount na payout-u je uvek u valuti listinga (bruto - porez u valuti listinga)
        assertEquals(0, new BigDecimal("17.0000").compareTo(payout.getNetAmount()), "neto u valuti listinga");
        assertEquals(701L, payout.getAccountId(), "accountId = RSD fallback racun drzaoca");

        // neto se kreditira u RSD na RSD racun (sa konverzijom), porez drzavi u RSD
        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("1989.0000")), eq(7L));
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("351.0000")), eq(7L));
    }

    // -------------------- WP-14b: legacy endpoint fallback (no id) --------------------

    @Test
    void payoutForHolder_personalRsdStock_resolvedViaLegacyEndpoint_accountIdNull() {
        // RSD stock, by-owner lookup ne nadje racun, ali stari /default endpoint ga vrati.
        // accountId ostaje null jer stari endpoint ne vraca id.
        Portfolio holder = holder(7L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(accountClient.defaultRsdAccountNumber(7L)).thenReturn("RSD-7");
        when(accountClient.stateRsdAccountNumber()).thenReturn("RSD-STATE");

        boolean paid = executor.payoutForHolder(stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId(), "stari endpoint ne vraca id -> accountId null");
        verify(accountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("42.5000")), eq(7L));
    }

    @Test
    void payoutForHolder_personalHolder_withoutAnyAccount_recordsPayoutButSkipsCredit() {
        // Drzalac nema ni RSD racun (by-owner ni legacy) -> isplata se evidentira bez kreditiranja.
        Portfolio holder = holder(7L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(accountClient.defaultRsdAccountNumber(7L)).thenReturn(null);
        when(accountClient.stateRsdAccountNumber()).thenReturn("RSD-STATE");

        boolean paid = executor.payoutForHolder(stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid, "isplata se i dalje evidentira");
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId(), "racun nije razresen -> accountId null");
        // racun drzaoca nije razresen -> ne kreditira se njemu, ali porez i dalje ide drzavi
        verify(accountClient, never()).creditAccount(eq("RSD-7"), any(), anyLong());
        verify(accountClient).creditAccount(eq("RSD-STATE"), eq(new BigDecimal("7.5000")), eq(7L));
    }

    // -------------------- bank-held holder: no tax --------------------

    @Test
    void payoutForHolder_bankHeld_noTax_fullGrossToBankProfit() {
        // Pozicija banke — bez poreza, pun bruto u Profit Banke.
        DividendPayoutExecutor bankExecutor = new DividendPayoutExecutor(
                accountClient, marketPriceClient, payoutRepository) {
            @Override
            boolean isBankHeld(Portfolio holder) {
                return true;
            }
        };
        ReflectionTestUtils.setField(bankExecutor, "taxRate", new BigDecimal("0.15"));

        Portfolio holder = holder(99L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(99L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK"));

        boolean paid = bankExecutor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid);
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        DividendPayout payout = captor.getValue();
        assertTrue(payout.isForBank());
        assertEquals(0, BigDecimal.ZERO.compareTo(payout.getTaxAmountRsd()), "pozicija banke = 0 poreza");
        assertEquals(0, payout.getGrossAmount().compareTo(payout.getNetAmount()), "neto = bruto za poziciju banke");
        assertEquals(1001L, payout.getAccountId(), "accountId = bankin RSD racun");

        // pun bruto na bankin racun, drzavni racun se NE dira
        verify(accountClient).creditAccount(eq("RSD-BANK"), eq(new BigDecimal("50.0000")), eq(99L));
        verify(accountClient, never()).stateRsdAccountNumber();
    }

    @Test
    void payoutForHolder_bankHeld_bankAccountUnresolved_recordsPayoutButSkipsCredit() {
        // Bankin racun se ne razresi -> isplata se evidentira, accountId null, bez kreditiranja.
        DividendPayoutExecutor bankExecutor = new DividendPayoutExecutor(
                accountClient, marketPriceClient, payoutRepository) {
            @Override
            boolean isBankHeld(Portfolio holder) {
                return true;
            }
        };
        ReflectionTestUtils.setField(bankExecutor, "taxRate", new BigDecimal("0.15"));

        Portfolio holder = holder(99L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(99L, 1L, AS_OF)).thenReturn(false);
        when(accountClient.bankRsdAccount()).thenReturn(null);

        boolean paid = bankExecutor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertTrue(paid, "isplata se i dalje evidentira");
        ArgumentCaptor<DividendPayout> captor = ArgumentCaptor.forClass(DividendPayout.class);
        verify(payoutRepository).save(captor.capture());
        assertNull(captor.getValue().getAccountId(), "bankin racun nije razresen -> accountId null");
        verify(accountClient, never()).creditAccount(any(), any(), anyLong());
    }

    // -------------------- idempotency --------------------

    @Test
    void payoutForHolder_skipsWhenAlreadyPaid() {
        Portfolio holder = holder(7L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(true);

        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), holder, AS_OF);

        assertFalse(paid, "vec isplaceno -> preskoci");
        verify(payoutRepository, never()).save(any());
        verify(accountClient, never()).creditAccount(any(), any(), anyLong());
    }

    @Test
    void payoutForHolder_skipsWhenGrossIsZero() {
        Portfolio holder = holder(7L, 100);
        when(payoutRepository.existsByUserIdAndListingIdAndPaymentDate(7L, 1L, AS_OF)).thenReturn(false);

        // yield = 0 -> gross = 0
        boolean paid = executor.payoutForHolder(
                stock("RSD", new BigDecimal("50"), BigDecimal.ZERO), holder, AS_OF);

        assertFalse(paid);
        verify(payoutRepository, never()).save(any());
    }
}
