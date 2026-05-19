package com.banka1.tradingservice.dividend.service;

import com.banka1.tradingservice.dividend.client.DividendAccountClient;
import com.banka1.tradingservice.dividend.client.DividendAccountClient.OwnerAccount;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.FundHoldingRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import com.banka1.tradingservice.funds.service.FundHoldingService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-17 (Celina 4.3): unit testovi za {@link FundDividendProcessor} —
 * obracun (formula), priliv u fond, {@code REINVEST} grana, {@code DISTRIBUTE}
 * grana (ukljucujuci poziciju banke {@code clientId = -1}).
 */
@ExtendWith(MockitoExtension.class)
class FundDividendProcessorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Mock private FundHoldingRepository fundHoldingRepository;
    @Mock private InvestmentFundRepository fundRepository;
    @Mock private ClientFundPositionRepository positionRepository;
    @Mock private ClientFundTransactionRepository transactionRepository;
    @Mock private FundHoldingService fundHoldingService;
    @Mock private MarketPriceClient marketPriceClient;
    @Mock private DividendAccountClient dividendAccountClient;
    @Mock private ObjectProvider<AccountServiceClient> accountServiceClientProvider;
    @Mock private AccountServiceClient accountServiceClient;

    private FundDividendProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new FundDividendProcessor(
                fundHoldingRepository, fundRepository, positionRepository, transactionRepository,
                fundHoldingService, marketPriceClient, dividendAccountClient, accountServiceClientProvider);
        // account-service dostupan po defaultu; testovi koji ga ne diraju koriste lenient.
        lenient().when(accountServiceClientProvider.getIfAvailable()).thenReturn(accountServiceClient);
        lenient().when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(fundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DividendDataClient.DividendData stock(String currency, BigDecimal price, BigDecimal yield) {
        return new DividendDataClient.DividendData(1L, "AAPL", price, currency, yield);
    }

    private FundHolding holding(Long id, Long fundId, int quantity) {
        return FundHolding.builder()
                .id(id)
                .fundId(fundId)
                .stockTicker("AAPL")
                .quantity(quantity)
                .avgUnitPrice(new BigDecimal("40.0000"))
                .deleted(false)
                .build();
    }

    private InvestmentFund fund(Long id, FundDividendPolicy policy, BigDecimal liquidity) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setNaziv("Alpha Growth");
        f.setMinimumContribution(new BigDecimal("1000"));
        f.setManagerId(50L);
        f.setLikvidnaSredstva(liquidity);
        f.setAccountNumber("1111111111111111");
        f.setDividendPolicy(policy);
        return f;
    }

    // -------------------- formula --------------------

    @Test
    void computeGross_appliesQuarterlyYieldFormula() {
        // 10 * 200 * (0.08 / 4) = 10 * 200 * 0.02 = 40.0000
        BigDecimal gross = processor.computeGross(10, new BigDecimal("200"), new BigDecimal("0.08"));
        assertEquals(0, new BigDecimal("40.0000").compareTo(gross), "gross = qty * price * yield/4");
    }

    @Test
    void computeGross_returnsZeroForNonPositiveOrNullInputs() {
        assertEquals(0, BigDecimal.ZERO.compareTo(processor.computeGross(0, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(processor.computeGross(null, new BigDecimal("10"), new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(processor.computeGross(5, null, new BigDecimal("0.1"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(processor.computeGross(5, new BigDecimal("10"), null)));
        assertEquals(0, BigDecimal.ZERO.compareTo(processor.computeGross(5, new BigDecimal("10"), BigDecimal.ZERO)));
    }

    // -------------------- inflow (always) --------------------

    @Test
    void processFundHolding_alwaysCreditsInflowToFundLiquidityAndAccount() {
        // RSD listing, holding 1 AAPL @ price 50, yield 0.04 -> gross = 1*50*0.01 = 0.50.
        // REINVEST default: floor(0.50 / 50) = 0 jedinica -> reinvest se preskace,
        // priliv ostaje cist; likvidnost 1000 -> 1000.50.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 1)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        assertEquals(0, new BigDecimal("1000.50").compareTo(f.getLikvidnaSredstva()),
                "priliv uvecava likvidnost; 0 jedinica reinvest -> ostaje");
        // RSD racun fonda kreditiran prilivom (ownerId = -1000 - fundId)
        verify(accountServiceClient).creditAccount(eq("1111111111111111"), eq(new BigDecimal("0.50")), eq(-1001L));
        // 0 jedinica -> nema kupovine reinvesticije
        verify(fundHoldingService, never()).addOrUpdate(anyLong(), any(), anyInt(), any());
    }

    @Test
    void processFundHolding_convertsForeignCurrencyDividendToRsdForInflow() {
        // USD listing: gross = 10 * 100 * (0.08/4) = 20.0000 USD; konverzija -> 2340 RSD.
        // REINVEST: floor(20 / 100) = 0 jedinica -> priliv ostaje.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("5000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 10)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(marketPriceClient.convertNoCommission(new BigDecimal("20.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("2340.0000")));

        boolean processed = processor.processFundHolding(
                stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), 10L, AS_OF);

        assertTrue(processed);
        assertEquals(0, new BigDecimal("7340.00").compareTo(f.getLikvidnaSredstva()),
                "5000 + 2340 RSD priliv");
        verify(accountServiceClient).creditAccount(eq("1111111111111111"), eq(new BigDecimal("2340.00")), eq(-1001L));
    }

    @Test
    void processFundHolding_skipsWhenGrossIsZero() {
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        // yield = 0 -> gross = 0
        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), BigDecimal.ZERO), 10L, AS_OF);

        assertFalse(processed, "bruto 0 -> preskoci");
        verify(fundRepository, never()).save(any());
        verify(accountServiceClient, never()).creditAccount(any(), any(), anyLong());
    }

    @Test
    void processFundHolding_skipsWhenHoldingGone() {
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.empty());

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertFalse(processed, "holding nestao -> preskoci");
        verifyNoInteractions(fundRepository, accountServiceClient);
    }

    @Test
    void processFundHolding_skipsWhenFundGone() {
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertFalse(processed, "fond nestao -> preskoci");
        verify(accountServiceClient, never()).creditAccount(any(), any(), anyLong());
    }

    @Test
    void processFundHolding_inflowToleratesMissingAccountServiceClient() {
        // account-service nije dostupan (local/test) -> priliv azurira likvidnost,
        // ali REST kreditiranje racuna fonda se preskace bez greske.
        when(accountServiceClientProvider.getIfAvailable()).thenReturn(null);
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 1)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        assertEquals(0, new BigDecimal("1000.50").compareTo(f.getLikvidnaSredstva()));
    }

    // -------------------- REINVEST --------------------

    @Test
    void processFundHolding_reinvest_buysWholeSharesAndDebitsCostFromLiquidity() {
        // RSD listing: holding 100 AAPL @ price 50, yield 0.04 -> gross = 100*50*0.01 = 50.00.
        // REINVEST: floor(50 / 50) = 1 jedinica @ 50 -> trosak 50 RSD.
        // likvidnost: 1000 + 50 (priliv) - 50 (reinvest) = 1000.00.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        // 1 cela jedinica kupljena za fond na ceni listinga
        verify(fundHoldingService).addOrUpdate(eq(1L), eq("AAPL"), eq(1), eq(new BigDecimal("50")));
        assertEquals(0, new BigDecimal("1000.00").compareTo(f.getLikvidnaSredstva()),
                "priliv +50, reinvest -50 -> neto 0 promene");
        // priliv ide na racun, reinvest ne dira racun fonda
        verify(accountServiceClient, times(1)).creditAccount(any(), any(), anyLong());
        // DISTRIBUTE grana se NE poziva
        verifyNoInteractions(positionRepository);
    }

    @Test
    void processFundHolding_reinvest_buysMultipleWholeShares() {
        // RSD listing: holding 1000 AAPL @ price 10, yield 0.04 -> gross = 1000*10*0.01 = 100.00.
        // REINVEST: floor(100 / 10) = 10 jedinica @ 10 -> trosak 100 RSD.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("500.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 1000)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("10"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        verify(fundHoldingService).addOrUpdate(eq(1L), eq("AAPL"), eq(10), eq(new BigDecimal("10")));
        assertEquals(0, new BigDecimal("500.00").compareTo(f.getLikvidnaSredstva()),
                "priliv +100, reinvest -100");
    }

    @Test
    void processFundHolding_reinvest_leavesAsLiquidityWhenZeroWholeShares() {
        // gross = 0.50 RSD < price 50 -> 0 celih jedinica; priliv ostaje kao likvidnost.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 1)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        verify(fundHoldingService, never()).addOrUpdate(anyLong(), any(), anyInt(), any());
        assertEquals(0, new BigDecimal("1000.50").compareTo(f.getLikvidnaSredstva()),
                "0 jedinica reinvest -> priliv ostaje cela likvidnost");
    }

    @Test
    void processFundHolding_reinvest_foreignCurrencyDebitsRsdEquivalentCost() {
        // USD listing: holding 10 AAPL @ price 100, yield 0.08 -> gross = 20 USD.
        // REINVEST: floor(20 / 100) = 0 jedinica; pa povecam holding da reinvest radi:
        // holding 100 AAPL -> gross = 100*100*0.02 = 200 USD; floor(200/100) = 2 jedinice.
        // trosak listing = 2*100 = 200 USD -> konverzija 200 USD -> 23400 RSD.
        // priliv: 200 USD -> 23400 RSD. likvidnost: 50000 + 23400 - 23400 = 50000.
        InvestmentFund f = fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("50000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(marketPriceClient.convertNoCommission(new BigDecimal("200.0000"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("23400.0000")));
        when(marketPriceClient.convertNoCommission(new BigDecimal("200"), "USD", "RSD"))
                .thenReturn(Optional.of(new BigDecimal("23400.00")));

        boolean processed = processor.processFundHolding(
                stock("USD", new BigDecimal("100"), new BigDecimal("0.08")), 10L, AS_OF);

        assertTrue(processed);
        verify(fundHoldingService).addOrUpdate(eq(1L), eq("AAPL"), eq(2), eq(new BigDecimal("100")));
        assertEquals(0, new BigDecimal("50000.00").compareTo(f.getLikvidnaSredstva()),
                "priliv 23400, reinvest -23400 -> neto 0");
    }

    // -------------------- DISTRIBUTE --------------------

    @Test
    void processFundHolding_distribute_splitsProportionallyAndDebitsLiquidity() {
        // RSD listing: holding 100 AAPL @ price 50, yield 0.04 -> gross = 50.00 RSD.
        // DISTRIBUTE: 2 pozicije — clientId 7 (totalInvested 3000), clientId 8 (1000).
        // udeo 7 = 50 * 3000/4000 = 37.50; udeo 8 = rezidual 50 - 37.50 = 12.50.
        // likvidnost: 1000 + 50 (priliv) - 50 (raspodela) = 1000.00 (neto nepromenjeno).
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(
                position(7L, 1L, new BigDecimal("3000.00")),
                position(8L, 1L, new BigDecimal("1000.00"))));
        when(dividendAccountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(dividendAccountClient.accountInCurrency(8L, "RSD")).thenReturn(new OwnerAccount(801L, "RSD-8"));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        verify(dividendAccountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("37.50")), eq(7L));
        verify(dividendAccountClient).creditAccount(eq("RSD-8"), eq(new BigDecimal("12.50")), eq(8L));
        assertEquals(0, new BigDecimal("1000.00").compareTo(f.getLikvidnaSredstva()),
                "priliv +50, raspodela -50 -> likvidnost fonda neto nepromenjena");
        // reinvest grana se NE poziva
        verify(fundHoldingService, never()).addOrUpdate(anyLong(), any(), anyInt(), any());
    }

    @Test
    void processFundHolding_distribute_recordsOutflowTransactionPerClient() {
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(
                position(7L, 1L, new BigDecimal("3000.00")),
                position(8L, 1L, new BigDecimal("1000.00"))));
        when(dividendAccountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(dividendAccountClient.accountInCurrency(8L, "RSD")).thenReturn(new OwnerAccount(801L, "RSD-8"));

        processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        ArgumentCaptor<ClientFundTransaction> captor = ArgumentCaptor.forClass(ClientFundTransaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        List<ClientFundTransaction> txs = captor.getAllValues();
        assertTrue(txs.stream().allMatch(t -> !t.isInflow()), "raspodela = outflow transakcije");
        assertTrue(txs.stream().allMatch(t -> t.getStatus() == ClientFundTransactionStatus.COMPLETED));
        assertTrue(txs.stream().allMatch(t -> t.getFundId().equals(1L)));
        assertEquals(0, new BigDecimal("37.50").compareTo(
                txs.stream().filter(t -> t.getClientId().equals(7L)).findFirst().orElseThrow().getAmount()));
    }

    @Test
    void processFundHolding_distribute_bankPositionReceivesProportionalShare() {
        // DISTRIBUTE sa bankinom pozicijom (clientId = -1, BANK_INVESTOR_ID).
        // gross = 50.00 RSD; pozicije: klijent 7 (totalInvested 2000), banka (2000).
        // udeo 7 = 50 * 2000/4000 = 25.00; udeo banke = rezidual 25.00.
        // banka prima na bankin RSD racun (Profit Banke).
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(
                position(7L, 1L, new BigDecimal("2000.00")),
                position(InvestmentFundService.BANK_INVESTOR_ID, 1L, new BigDecimal("2000.00"))));
        when(dividendAccountClient.accountInCurrency(7L, "RSD")).thenReturn(new OwnerAccount(701L, "RSD-7"));
        when(dividendAccountClient.bankRsdAccount()).thenReturn(new OwnerAccount(1001L, "RSD-BANK"));

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        verify(dividendAccountClient).creditAccount(eq("RSD-7"), eq(new BigDecimal("25.00")), eq(7L));
        // banka prima udeo na bankin RSD racun
        verify(dividendAccountClient).creditAccount(
                eq("RSD-BANK"), eq(new BigDecimal("25.00")), eq(InvestmentFundService.BANK_INVESTOR_ID));
        // bankina pozicija ne razresava se preko accountInCurrency
        verify(dividendAccountClient, never()).accountInCurrency(eq(InvestmentFundService.BANK_INVESTOR_ID), any());
    }

    @Test
    void processFundHolding_distribute_fallsBackToLegacyRsdEndpointWhenNoCurrencyAccount() {
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(
                position(7L, 1L, new BigDecimal("4000.00"))));
        when(dividendAccountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(dividendAccountClient.defaultRsdAccountNumber(7L)).thenReturn("RSD-7-LEGACY");

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        // jedina pozicija prima ceo gross (rezidual)
        verify(dividendAccountClient).creditAccount(eq("RSD-7-LEGACY"), eq(new BigDecimal("50.00")), eq(7L));
    }

    @Test
    void processFundHolding_distribute_recordsFailedTransactionWhenAccountUnresolved() {
        // Klijentu se ne moze razresiti RSD racun -> outflow transakcija FAILED, bez kreditiranja.
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(
                position(7L, 1L, new BigDecimal("4000.00"))));
        when(dividendAccountClient.accountInCurrency(7L, "RSD")).thenReturn(null);
        when(dividendAccountClient.defaultRsdAccountNumber(7L)).thenReturn(null);

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed, "obrada se i dalje evidentira");
        ArgumentCaptor<ClientFundTransaction> captor = ArgumentCaptor.forClass(ClientFundTransaction.class);
        verify(transactionRepository).save(captor.capture());
        assertEquals(ClientFundTransactionStatus.FAILED, captor.getValue().getStatus(),
                "racun nije razresen -> FAILED");
        verify(dividendAccountClient, never()).creditAccount(any(), any(), anyLong());
    }

    @Test
    void processFundHolding_distribute_keepsInflowAsLiquidityWhenNoPositions() {
        // DISTRIBUTE ali fond nema pozicija sa ulogom -> priliv ostaje kao likvidnost.
        InvestmentFund f = fund(1L, FundDividendPolicy.DISTRIBUTE, new BigDecimal("1000.00"));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 100)));
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(f));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of());

        boolean processed = processor.processFundHolding(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), 10L, AS_OF);

        assertTrue(processed);
        assertEquals(0, new BigDecimal("1050.00").compareTo(f.getLikvidnaSredstva()),
                "nema pozicija -> priliv ostaje kao likvidnost (nije oduzet)");
        verify(dividendAccountClient, never()).creditAccount(any(), any(), anyLong());
        verify(transactionRepository, never()).save(any());
    }

    // -------------------- processStockForFunds orchestration --------------------

    @Test
    void processStockForFunds_processesEveryFundHoldingOfTicker() {
        DividendDataClient.DividendData s = stock("RSD", new BigDecimal("50"), new BigDecimal("0.04"));
        when(fundHoldingRepository.findByStockTickerAndDeletedFalse("AAPL")).thenReturn(List.of(
                holding(10L, 1L, 1), holding(11L, 2L, 1)));
        when(fundHoldingRepository.findById(10L)).thenReturn(Optional.of(holding(10L, 1L, 1)));
        when(fundHoldingRepository.findById(11L)).thenReturn(Optional.of(holding(11L, 2L, 1)));
        when(fundRepository.findByIdForUpdate(1L))
                .thenReturn(Optional.of(fund(1L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"))));
        when(fundRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(fund(2L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"))));

        int processed = processor.processStockForFunds(s, AS_OF);

        assertEquals(2, processed, "obe fond-pozicije obradjene");
    }

    @Test
    void processStockForFunds_returnsZeroWhenNoFundHoldsTicker() {
        when(fundHoldingRepository.findByStockTickerAndDeletedFalse("AAPL")).thenReturn(List.of());

        int processed = processor.processStockForFunds(
                stock("RSD", new BigDecimal("50"), new BigDecimal("0.04")), AS_OF);

        assertEquals(0, processed);
        verify(fundRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void processStockForFunds_continuesWhenOneFundHoldingThrows() {
        DividendDataClient.DividendData s = stock("RSD", new BigDecimal("50"), new BigDecimal("0.04"));
        when(fundHoldingRepository.findByStockTickerAndDeletedFalse("AAPL")).thenReturn(List.of(
                holding(10L, 1L, 1), holding(11L, 2L, 1)));
        // holding 10 baca (fund lookup throws), holding 11 prolazi
        when(fundHoldingRepository.findById(10L)).thenThrow(new RuntimeException("db down"));
        when(fundHoldingRepository.findById(11L)).thenReturn(Optional.of(holding(11L, 2L, 1)));
        when(fundRepository.findByIdForUpdate(2L))
                .thenReturn(Optional.of(fund(2L, FundDividendPolicy.REINVEST, new BigDecimal("1000.00"))));

        int processed = processor.processStockForFunds(s, AS_OF);

        assertEquals(1, processed, "greska na jednom holdingu ne obara obradu ostalih");
    }

    private ClientFundPosition position(Long clientId, Long fundId, BigDecimal totalInvested) {
        ClientFundPosition p = new ClientFundPosition();
        p.setClientId(clientId);
        p.setFundId(fundId);
        p.setTotalInvested(totalInvested);
        return p;
    }
}
