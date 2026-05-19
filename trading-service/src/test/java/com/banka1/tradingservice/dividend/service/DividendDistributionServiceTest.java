package com.banka1.tradingservice.dividend.service;

import com.banka1.order.entity.Portfolio;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-14: unit testovi za {@link DividendDistributionService} — orkestracija:
 * obilazak hartija/drzaoca, preskakanje hartija bez pozitivne stope,
 * otpornost na gresku jedne isplate.
 */
@ExtendWith(MockitoExtension.class)
class DividendDistributionServiceTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 31);

    @Mock
    private DividendDataClient dividendDataClient;
    @Mock
    private PortfolioRepository portfolioRepository;
    @Mock
    private DividendPayoutExecutor payoutExecutor;
    @Mock
    private FundDividendProcessor fundDividendProcessor;

    @InjectMocks
    private DividendDistributionService service;

    private Portfolio holder(Long userId) {
        Portfolio p = new Portfolio();
        p.setUserId(userId);
        p.setListingId(1L);
        p.setListingType(ListingType.STOCK);
        p.setQuantity(100);
        return p;
    }

    @Test
    void distribute_returnsZeroWhenNoDividendData() {
        when(dividendDataClient.fetchAll()).thenReturn(List.of());

        assertEquals(0, service.distribute(AS_OF));
        verifyNoInteractions(portfolioRepository, payoutExecutor, fundDividendProcessor);
    }

    @Test
    void distribute_skipsStocksWithoutPositiveYield() {
        when(dividendDataClient.fetchAll()).thenReturn(List.of(
                new DividendDataClient.DividendData(1L, "ZERO", new BigDecimal("10"), "RSD", BigDecimal.ZERO),
                new DividendDataClient.DividendData(2L, "NULLY", new BigDecimal("10"), "RSD", null)));

        assertEquals(0, service.distribute(AS_OF));
        verify(portfolioRepository, never()).findByListingIdStockHolders(anyLong());
        verifyNoInteractions(payoutExecutor, fundDividendProcessor);
    }

    @Test
    void distribute_processesFundHoldingsForEachDividendStock() {
        // WP-17: posle drzaoca-pojedinaca, fond-pozicije iste hartije se obradjuju.
        DividendDataClient.DividendData stock =
                new DividendDataClient.DividendData(1L, "AAPL", new BigDecimal("50"), "RSD", new BigDecimal("0.04"));
        when(dividendDataClient.fetchAll()).thenReturn(List.of(stock));
        when(portfolioRepository.findByListingIdStockHolders(1L)).thenReturn(List.of(holder(7L)));
        when(payoutExecutor.payoutForHolder(any(), any(), eq(AS_OF))).thenReturn(true);
        when(fundDividendProcessor.processStockForFunds(stock, AS_OF)).thenReturn(2);

        int paid = service.distribute(AS_OF);

        assertEquals(1, paid, "povratna vrednost broji samo Portfolio isplate");
        verify(fundDividendProcessor).processStockForFunds(stock, AS_OF);
    }

    @Test
    void distribute_continuesWhenFundProcessingThrows() {
        // WP-17: greska u obradi fondova jedne hartije ne obara ceo obracun.
        DividendDataClient.DividendData stock =
                new DividendDataClient.DividendData(1L, "AAPL", new BigDecimal("50"), "RSD", new BigDecimal("0.04"));
        when(dividendDataClient.fetchAll()).thenReturn(List.of(stock));
        when(portfolioRepository.findByListingIdStockHolders(1L)).thenReturn(List.of(holder(7L)));
        when(payoutExecutor.payoutForHolder(any(), any(), eq(AS_OF))).thenReturn(true);
        when(fundDividendProcessor.processStockForFunds(stock, AS_OF))
                .thenThrow(new RuntimeException("fund holding repo down"));

        int paid = service.distribute(AS_OF);

        assertEquals(1, paid, "Portfolio isplata prolazi i pored greske u obradi fondova");
    }

    @Test
    void distribute_paysEveryHolderOfEveryDividendStock() {
        when(dividendDataClient.fetchAll()).thenReturn(List.of(
                new DividendDataClient.DividendData(1L, "AAPL", new BigDecimal("50"), "RSD", new BigDecimal("0.04"))));
        when(portfolioRepository.findByListingIdStockHolders(1L))
                .thenReturn(List.of(holder(7L), holder(8L)));
        when(payoutExecutor.payoutForHolder(any(), any(), eq(AS_OF))).thenReturn(true);

        int paid = service.distribute(AS_OF);

        assertEquals(2, paid, "obe pozicije isplacene");
        verify(payoutExecutor, times(2)).payoutForHolder(any(), any(), eq(AS_OF));
    }

    @Test
    void distribute_countsOnlyExecutedPayouts() {
        when(dividendDataClient.fetchAll()).thenReturn(List.of(
                new DividendDataClient.DividendData(1L, "AAPL", new BigDecimal("50"), "RSD", new BigDecimal("0.04"))));
        Portfolio h1 = holder(7L);
        Portfolio h2 = holder(8L);
        when(portfolioRepository.findByListingIdStockHolders(1L)).thenReturn(List.of(h1, h2));
        // h1 vec isplacen (executor vraca false), h2 isplacen
        when(payoutExecutor.payoutForHolder(any(), eq(h1), eq(AS_OF))).thenReturn(false);
        when(payoutExecutor.payoutForHolder(any(), eq(h2), eq(AS_OF))).thenReturn(true);

        assertEquals(1, service.distribute(AS_OF), "broji samo stvarno izvrsene isplate");
    }

    @Test
    void distribute_continuesWhenOneHolderPayoutThrows() {
        when(dividendDataClient.fetchAll()).thenReturn(List.of(
                new DividendDataClient.DividendData(1L, "AAPL", new BigDecimal("50"), "RSD", new BigDecimal("0.04"))));
        Portfolio failing = holder(7L);
        Portfolio ok = holder(8L);
        when(portfolioRepository.findByListingIdStockHolders(1L)).thenReturn(List.of(failing, ok));
        when(payoutExecutor.payoutForHolder(any(), eq(failing), eq(AS_OF)))
                .thenThrow(new RuntimeException("account-service down"));
        when(payoutExecutor.payoutForHolder(any(), eq(ok), eq(AS_OF))).thenReturn(true);

        int paid = service.distribute(AS_OF);

        assertEquals(1, paid, "greska na jednom drzaocu ne obara obracun za ostale");
    }
}
