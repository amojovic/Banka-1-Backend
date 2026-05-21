package com.banka1.tradingservice.funds.service;

import com.banka1.order.client.AccountClient;
import com.banka1.order.dto.client.PaymentDto;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.MarketPriceClient;
import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.FundDividendDistribution;
import com.banka1.tradingservice.funds.domain.FundDividendStrategy;
import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.RecordFundDividendRequest;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.FundDividendDistributionRepository;
import com.banka1.tradingservice.funds.repository.FundDividendPayoutRepository;
import com.banka1.tradingservice.funds.repository.FundHoldingRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FundDividendServiceTest {

    @Mock private InvestmentFundRepository fundRepository;
    @Mock private FundHoldingRepository holdingRepository;
    @Mock private FundDividendDistributionRepository distributionRepository;
    @Mock private FundDividendPayoutRepository payoutRepository;
    @Mock private ClientFundPositionRepository positionRepository;
    @Mock private FundHoldingService fundHoldingService;
    @Mock private InvestmentFundService investmentFundService;
    @Mock private FundValueSnapshotService snapshotService;
    @Mock private MarketPriceClient marketPriceClient;
    @Mock private AccountServiceClient accountServiceClient;
    @Mock private AccountClient accountClient;

    @InjectMocks private FundDividendService service;

    private InvestmentFund fund;
    private FundHolding holding;

    @BeforeEach
    void setUp() {
        fund = new InvestmentFund();
        fund.setId(1L);
        fund.setNaziv("Alpha");
        fund.setLikvidnaSredstva(new BigDecimal("1000.00"));
        fund.setAccountNumber("1234567812345678");
        fund.setDividendStrategy(FundDividendStrategy.REINVEST);

        holding = FundHolding.builder()
                .id(10L)
                .fundId(1L)
                .stockTicker("AAPL")
                .quantity(20)
                .avgUnitPrice(new BigDecimal("10.0000"))
                .build();

        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fund));
        when(holdingRepository.findByFundIdAndStockTickerAndDeletedFalse(1L, "AAPL")).thenReturn(Optional.of(holding));
        when(distributionRepository.findByFundIdAndStockTickerAndPaymentDate(1L, "AAPL", LocalDate.of(2026, 5, 20)))
                .thenReturn(Optional.empty());
        when(distributionRepository.save(any(FundDividendDistribution.class))).thenAnswer(inv -> {
            FundDividendDistribution distribution = inv.getArgument(0);
            distribution.setId(55L);
            return distribution;
        });
        doNothing().when(accountServiceClient).creditAccount(any(), any(), any());
    }

    @Test
    void recordDividend_reinvestiraKadJeStrategijaReinvest() {
        when(marketPriceClient.currentPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("5.00")));
        when(marketPriceClient.convertNoCommission(any(), eq("USD"), eq("RSD")))
                .thenAnswer(inv -> {
                    BigDecimal amount = inv.getArgument(0);
                    if (amount.compareTo(new BigDecimal("10.00")) == 0) {
                        return Optional.of(new BigDecimal("1000.00"));
                    }
                    if (amount.compareTo(new BigDecimal("5.00")) == 0) {
                        return Optional.of(new BigDecimal("500.00"));
                    }
                    return Optional.of(amount);
                });

        RecordFundDividendRequest request = new RecordFundDividendRequest();
        request.setStockTicker("AAPL");
        request.setDividendPerShare(new BigDecimal("0.50"));
        request.setCurrency("USD");
        request.setPaymentDate(LocalDate.of(2026, 5, 20));
        request.setStrategy(FundDividendStrategy.REINVEST);

        var response = service.recordDividend(1L, request);

        assertThat(response.getReinvestedShares()).isEqualTo(2);
        verify(fundHoldingService).addOrUpdate(1L, "AAPL", 2, new BigDecimal("5.00"));
        verify(investmentFundService).debitLiquidity(1L, new BigDecimal("1000.00"), "Fund dividend reinvestment");
    }

    @Test
    void recordDividend_isplacujeKlijentimaProporcionalno() {
        fund.setDividendStrategy(FundDividendStrategy.PAYOUT_CLIENTS);
        when(marketPriceClient.convertNoCommission(any(), eq("USD"), eq("RSD")))
                .thenReturn(Optional.of(new BigDecimal("1000.00")));
        when(fundHoldingService.calculateHoldingsValue(1L)).thenReturn(new BigDecimal("5000.00"));

        ClientFundPosition p1 = new ClientFundPosition();
        p1.setClientId(101L);
        p1.setFundId(1L);
        p1.setTotalInvested(new BigDecimal("2000.00"));
        ClientFundPosition p2 = new ClientFundPosition();
        p2.setClientId(102L);
        p2.setFundId(1L);
        p2.setTotalInvested(new BigDecimal("3000.00"));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(p1, p2));
        when(accountClient.getDefaultRsdAccountNumberForOwner(101L)).thenReturn("111");
        when(accountClient.getDefaultRsdAccountNumberForOwner(102L)).thenReturn("222");

        RecordFundDividendRequest request = new RecordFundDividendRequest();
        request.setStockTicker("AAPL");
        request.setDividendPerShare(new BigDecimal("0.50"));
        request.setCurrency("USD");
        request.setPaymentDate(LocalDate.of(2026, 5, 20));
        request.setStrategy(FundDividendStrategy.PAYOUT_CLIENTS);

        var response = service.recordDividend(1L, request);

        assertThat(response.getPayouts()).hasSize(2);
        assertThat(response.getDistributedAmountRsd()).isEqualByComparingTo("1000.00");
        assertThat(response.getPayouts())
                .extracting(payout -> payout.getAmountRsd().toPlainString())
                .containsExactly("333.33", "666.67");
        verify(accountClient, times(2)).transaction(any(PaymentDto.class));
        verify(investmentFundService, times(2))
                .debitLiquidity(eq(1L), any(), eq("Fund dividend payout"));
    }

    @Test
    void recordDividend_kadNemaKlijenata_ostavljaLikvidnostIFond() {
        fund.setDividendStrategy(FundDividendStrategy.PAYOUT_CLIENTS);
        when(marketPriceClient.convertNoCommission(any(), eq("USD"), eq("RSD")))
                .thenReturn(Optional.of(new BigDecimal("1000.00")));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of());

        RecordFundDividendRequest request = new RecordFundDividendRequest();
        request.setStockTicker("AAPL");
        request.setDividendPerShare(new BigDecimal("0.50"));
        request.setCurrency("USD");
        request.setPaymentDate(LocalDate.of(2026, 5, 20));
        request.setStrategy(FundDividendStrategy.PAYOUT_CLIENTS);

        var response = service.recordDividend(1L, request);

        assertThat(response.getPayouts()).isEmpty();
        verify(accountClient, never()).transaction(any(PaymentDto.class));
    }
}
