package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.ClientFundTransactionStatus;
import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.FundPerformancePointDto;
import com.banka1.tradingservice.funds.dto.FundStatisticsDto;
import com.banka1.tradingservice.funds.dto.FundValueSnapshotDto;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.client.AccountServiceClient;
import com.banka1.tradingservice.funds.client.UserServiceClient;
import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.repository.ClientFundPositionRepository;
import com.banka1.tradingservice.funds.repository.ClientFundTransactionRepository;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InvestmentFundServiceTest {

    @Mock private InvestmentFundRepository fundRepository;
    @Mock private ClientFundPositionRepository positionRepository;
    @Mock private ClientFundTransactionRepository transactionRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private FundAccountNumberGenerator accountNumberGenerator;
    @Mock private FundHoldingService fundHoldingService;
    @Mock private FundValueSnapshotRepository snapshotRepository;
    @Mock private FundStatisticsService fundStatisticsService;
    @Mock private ObjectProvider<AccountServiceClient> accountServiceClientProvider;
    @Mock private ObjectProvider<UserServiceClient> userServiceClientProvider;

    @InjectMocks private InvestmentFundService service;

    private InvestmentFund fixture;

    @BeforeEach
    void setUp() {
        fixture = new InvestmentFund();
        fixture.setId(1L);
        fixture.setNaziv("Alpha Growth");
        fixture.setMinimumContribution(new BigDecimal("1000"));
        fixture.setLikvidnaSredstva(new BigDecimal("100000"));
        fixture.setManagerId(50L);
        fixture.setAccountNumber("1234567812345674");

        // holdings value = 0 unless overridden per test
        lenient().when(fundHoldingService.calculateHoldingsValue(anyLong())).thenReturn(BigDecimal.ZERO);
        // no account-service available in unit tests — fund creation skips REST call
        lenient().when(accountServiceClientProvider.getIfAvailable()).thenReturn(null);
        // no user-service available in unit tests — toDto skips manager name lookup
        lenient().when(userServiceClientProvider.getIfAvailable()).thenReturn(null);
        // WP-18: toDto enriches each fund with statistics — default to "not enough snapshots"
        lenient().when(fundStatisticsService.computeStatistics(anyLong()))
                .thenReturn(FundStatisticsDto.builder().metricsAvailable(false).build());
    }

    @Test
    void createFund_kreira_sa_zero_likvidnih_i_generickim_account() {
        when(accountNumberGenerator.generate()).thenReturn("9999999999999999");
        when(fundRepository.save(any())).thenAnswer(inv -> {
            InvestmentFund f = inv.getArgument(0);
            f.setId(2L);
            return f;
        });
        when(positionRepository.findByFundId(2L)).thenReturn(List.of());

        CreateFundRequest req = new CreateFundRequest("Beta Fund", "Tech", new BigDecimal("500"));

        InvestmentFundDto dto = service.createFund(req, 60L);

        assertThat(dto.getNaziv()).isEqualTo("Beta Fund");
        assertThat(dto.getLikvidnaSredstva()).isEqualByComparingTo("0");
        assertThat(dto.getAccountNumber()).isEqualTo("9999999999999999");
        assertThat(dto.getManagerId()).isEqualTo(60L);
    }

    @Test
    void invest_throws_kadJeAmountManjiOdMinimum() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        InvestmentRequest req = new InvestmentRequest(new BigDecimal("500"), "ACC-1");

        assertThatThrownBy(() -> service.invest(1L, 100L, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minimumContribution");
    }

    @Test
    void invest_kreira_pending_transaction() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            ClientFundTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        ClientFundTransaction tx = service.invest(1L, 100L,
                new InvestmentRequest(new BigDecimal("5000"), "ACC-1"));

        assertThat(tx.getStatus()).isEqualTo(ClientFundTransactionStatus.PENDING);
        assertThat(tx.isInflow()).isTrue();
        assertThat(tx.getAmount()).isEqualByComparingTo("5000");
    }

    @Test
    void redeem_throws_kadKlijentNemaPoziciju() {
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.redeem(1L, 100L, new RedemptionRequest(new BigDecimal("500"), "ACC-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nema poziciju");
    }

    @Test
    void redeem_throws_kadJeAmountVeciOdTrenutnePozicijeVrednosti() {
        // Small fund — liquidity 2000, sole investor with 1000 invested
        // -> currentPositionValue = (1000/1000) * 2000 = 2000
        // -> request 5000 > 2000 -> exception
        InvestmentFund smallFund = new InvestmentFund();
        smallFund.setId(1L);
        smallFund.setLikvidnaSredstva(new BigDecimal("2000"));
        smallFund.setMinimumContribution(BigDecimal.ONE);
        smallFund.setAccountNumber("ACC");

        ClientFundPosition pos = new ClientFundPosition();
        pos.setTotalInvested(new BigDecimal("1000"));

        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(smallFund));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.of(pos));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(pos));

        assertThatThrownBy(() ->
                service.redeem(1L, 100L, new RedemptionRequest(new BigDecimal("5000"), "ACC-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("veca");
    }

    @Test
    void redeem_kreira_pending_transaction_outflow() {
        // fixture liquidity = 100000, sole investor with 10000 invested
        // -> currentPositionValue = 100000 -> 3000 < 100000 -> OK
        ClientFundPosition pos = new ClientFundPosition();
        pos.setTotalInvested(new BigDecimal("10000"));

        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(positionRepository.findByClientIdAndFundIdForUpdate(100L, 1L)).thenReturn(Optional.of(pos));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(pos));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            ClientFundTransaction t = inv.getArgument(0);
            t.setId(99L);
            return t;
        });

        ClientFundTransaction tx = service.redeem(1L, 100L,
                new RedemptionRequest(new BigDecimal("3000"), "ACC-1"));

        assertThat(tx.isInflow()).isFalse();
        assertThat(tx.getStatus()).isEqualTo(ClientFundTransactionStatus.PENDING);
    }

    @Test
    void reassignManager_prebacuje_sve_fondove() {
        InvestmentFund f1 = new InvestmentFund();
        f1.setManagerId(50L);
        InvestmentFund f2 = new InvestmentFund();
        f2.setManagerId(50L);
        when(fundRepository.findByManagerIdAndDeletedFalse(50L)).thenReturn(List.of(f1, f2));

        service.reassignManager(50L, 75L);

        assertThat(f1.getManagerId()).isEqualTo(75L);
        assertThat(f2.getManagerId()).isEqualTo(75L);
    }

    // -------------------- WP-17: updateDividendPolicy --------------------

    @Test
    void updateDividendPolicy_menja_politiku_i_vraca_dto() {
        // fixture default REINVEST -> DISTRIBUTE
        when(fundRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(fixture));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of());

        InvestmentFundDto dto = service.updateDividendPolicy(1L, FundDividendPolicy.DISTRIBUTE);

        assertThat(fixture.getDividendPolicy()).isEqualTo(FundDividendPolicy.DISTRIBUTE);
        assertThat(dto.getDividendPolicy()).isEqualTo(FundDividendPolicy.DISTRIBUTE);
        verify(fundRepository).save(fixture);
    }

    @Test
    void updateDividendPolicy_throws_kadFondNePostoji() {
        when(fundRepository.findByIdForUpdate(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDividendPolicy(404L, FundDividendPolicy.REINVEST))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }

    @Test
    void updateDividendPolicy_throws_kadJePolitikaNull() {
        assertThatThrownBy(() -> service.updateDividendPolicy(1L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("obavezan");
    }

    // -------------------- WP-18: computeFundValuation --------------------

    @Test
    void computeFundValuation_vracaVrednostIProfitFonda() {
        // likvidnaSredstva 100000 + holdings 20000 = totalValue 120000
        // invested 70000 -> profit 50000
        when(fundRepository.findById(1L)).thenReturn(Optional.of(fixture));
        when(fundHoldingService.calculateHoldingsValue(1L)).thenReturn(new BigDecimal("20000"));
        ClientFundPosition p = new ClientFundPosition();
        p.setTotalInvested(new BigDecimal("70000"));
        when(positionRepository.findByFundId(1L)).thenReturn(List.of(p));

        InvestmentFundService.FundValuation v = service.computeFundValuation(1L);

        assertThat(v.totalValue()).isEqualByComparingTo("120000");
        assertThat(v.profit()).isEqualByComparingTo("50000");
    }

    @Test
    void computeFundValuation_throws_kadFondNePostoji() {
        when(fundRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.computeFundValuation(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }

    // -------------------- WP-18: discovery sorting --------------------

    private InvestmentFund namedFund(Long id, String naziv) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setNaziv(naziv);
        f.setMinimumContribution(BigDecimal.ZERO);
        f.setLikvidnaSredstva(BigDecimal.ZERO);
        f.setManagerId(50L);
        f.setAccountNumber("ACC-" + id);
        return f;
    }

    @Test
    void discovery_defaultSort_jeNazivRastuce() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(namedFund(1L, "Beta"), namedFund(2L, "Alpha")));
        when(positionRepository.findByFundId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());

        List<InvestmentFundDto> result = service.discovery();

        assertThat(result).extracting(InvestmentFundDto::getNaziv).containsExactly("Alpha", "Beta");
    }

    @Test
    void discovery_sortByVolatilityDesc_nedostupneMetrikeIdunaKraj() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(namedFund(1L, "A"), namedFund(2L, "B"), namedFund(3L, "C")));
        when(positionRepository.findByFundId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        // fund 1 vol=0.10, fund 2 vol=0.30, fund 3 vol=null (not enough snapshots)
        when(fundStatisticsService.computeStatistics(1L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(true).volatility(new BigDecimal("0.10")).build());
        when(fundStatisticsService.computeStatistics(2L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(true).volatility(new BigDecimal("0.30")).build());
        when(fundStatisticsService.computeStatistics(3L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(false).build());

        List<InvestmentFundDto> desc = service.discovery(
                InvestmentFundService.FundSortField.VOLATILITY, false);

        // desc: 0.30, 0.10, then null last
        assertThat(desc).extracting(InvestmentFundDto::getId).containsExactly(2L, 1L, 3L);
    }

    @Test
    void discovery_sortByVolatilityAsc_nedostupneMetrikeIdunaKrajIDalje() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(namedFund(1L, "A"), namedFund(2L, "B"), namedFund(3L, "C")));
        when(positionRepository.findByFundId(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        when(fundStatisticsService.computeStatistics(1L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(true).volatility(new BigDecimal("0.10")).build());
        when(fundStatisticsService.computeStatistics(2L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(true).volatility(new BigDecimal("0.30")).build());
        when(fundStatisticsService.computeStatistics(3L)).thenReturn(
                FundStatisticsDto.builder().metricsAvailable(false).build());

        List<InvestmentFundDto> asc = service.discovery(
                InvestmentFundService.FundSortField.VOLATILITY, true);

        // asc: 0.10, 0.30, then null STILL last (not first)
        assertThat(asc).extracting(InvestmentFundDto::getId).containsExactly(1L, 2L, 3L);
    }

    // -------------------- WP-18: fundPerformance (real snapshots) --------------------

    @Test
    void fundPerformance_vracaSerijuIzSnapshotova() {
        when(fundRepository.findById(1L)).thenReturn(Optional.of(fixture));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(1L)).thenReturn(List.of(
                snapshot(1L, LocalDate.of(2026, 1, 1), "100000", "0"),
                snapshot(1L, LocalDate.of(2026, 2, 1), "110000", "10000")));

        List<FundPerformancePointDto> series = service.fundPerformance(1L);

        assertThat(series).hasSize(2);
        // points are NOT identical anymore (the old fake stamped one constant value)
        assertThat(series.get(0).getTotalValue()).isEqualByComparingTo("100000");
        assertThat(series.get(1).getTotalValue()).isEqualByComparingTo("110000");
        assertThat(series.get(0).getProfit()).isEqualByComparingTo("0");
        assertThat(series.get(1).getProfit()).isEqualByComparingTo("10000");
    }

    @Test
    void fundPerformance_bezSnapshotova_vracaJednuTackuTrenutneValuacije() {
        when(fundRepository.findById(1L)).thenReturn(Optional.of(fixture));
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(1L)).thenReturn(List.of());
        when(positionRepository.findByFundId(1L)).thenReturn(List.of());

        List<FundPerformancePointDto> series = service.fundPerformance(1L);

        assertThat(series).hasSize(1);
        // likvidnaSredstva 100000 + holdings 0 - invested 0 -> totalValue 100000, profit 100000
        assertThat(series.get(0).getTotalValue()).isEqualByComparingTo("100000");
    }

    // -------------------- WP-18: value history + average --------------------

    @Test
    void fundValueHistory_vracaSnapshotSeriju() {
        when(fundRepository.existsById(1L)).thenReturn(true);
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(1L)).thenReturn(List.of(
                snapshot(1L, LocalDate.of(2026, 1, 1), "100000", "0"),
                snapshot(1L, LocalDate.of(2026, 2, 1), "120000", "20000")));

        List<FundValueSnapshotDto> history = service.fundValueHistory(1L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).getSnapshotDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(history.get(1).getTotalValue()).isEqualByComparingTo("120000");
    }

    @Test
    void fundValueHistory_throws_kadFondNePostoji() {
        when(fundRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.fundValueHistory(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }

    @Test
    void averageValueHistory_racunaProsekPoDatumuPrekoFondova() {
        // date 2026-01-01: funds 100000 and 200000 -> avg 150000
        // date 2026-02-01: single fund 300000 -> avg 300000
        when(snapshotRepository.findAllByOrderBySnapshotDateAsc()).thenReturn(List.of(
                snapshot(1L, LocalDate.of(2026, 1, 1), "100000", "0"),
                snapshot(2L, LocalDate.of(2026, 1, 1), "200000", "40000"),
                snapshot(1L, LocalDate.of(2026, 2, 1), "300000", "60000")));

        List<FundValueSnapshotDto> avg = service.averageValueHistory();

        assertThat(avg).hasSize(2);
        assertThat(avg.get(0).getFundId()).as("prosecna serija nije vezana za fond").isNull();
        assertThat(avg.get(0).getSnapshotDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(avg.get(0).getTotalValue()).isEqualByComparingTo("150000.0000");
        assertThat(avg.get(0).getProfit()).isEqualByComparingTo("20000.0000");
        assertThat(avg.get(1).getTotalValue()).isEqualByComparingTo("300000.0000");
    }

    @Test
    void fundStatistics_delegira_naStatisticsService() {
        when(fundRepository.existsById(1L)).thenReturn(true);
        FundStatisticsDto expected = FundStatisticsDto.builder()
                .fundId(1L).metricsAvailable(true).volatility(new BigDecimal("0.05")).build();
        when(fundStatisticsService.computeStatistics(1L)).thenReturn(expected);

        FundStatisticsDto result = service.fundStatistics(1L);

        assertThat(result).isSameAs(expected);
    }

    @Test
    void fundStatistics_throws_kadFondNePostoji() {
        when(fundRepository.existsById(404L)).thenReturn(false);

        assertThatThrownBy(() -> service.fundStatistics(404L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne postoji");
    }

    private static FundValueSnapshot snapshot(Long fundId, LocalDate date, String totalValue, String profit) {
        return FundValueSnapshot.builder()
                .fundId(fundId)
                .snapshotDate(date)
                .totalValue(new BigDecimal(totalValue))
                .profit(new BigDecimal(profit))
                .build();
    }
}
