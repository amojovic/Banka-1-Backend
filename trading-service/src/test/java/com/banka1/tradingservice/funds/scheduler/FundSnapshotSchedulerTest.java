package com.banka1.tradingservice.funds.scheduler;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-18 (Celina 4.4): unit testovi za {@link FundSnapshotScheduler} — mesecni
 * cron koji za svaki fond materijalizuje vrednost+profit u {@link FundValueSnapshot}.
 *
 * <p>{@link Clock} je injektovan da je {@code snapshotDate} deterministican.
 */
@ExtendWith(MockitoExtension.class)
class FundSnapshotSchedulerTest {

    @Mock private InvestmentFundRepository fundRepository;
    @Mock private FundValueSnapshotRepository snapshotRepository;
    @Mock private InvestmentFundService fundService;

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    private FundSnapshotScheduler scheduler() {
        Clock fixed = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
        return new FundSnapshotScheduler(fundRepository, snapshotRepository, fundService, fixed);
    }

    private static InvestmentFund fund(Long id) {
        InvestmentFund f = new InvestmentFund();
        f.setId(id);
        f.setNaziv("Fund " + id);
        return f;
    }

    @Test
    void writesOneSnapshotPerFund_withTodayDate() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(fund(1L), fund(2L)));
        when(snapshotRepository.existsByFundIdAndSnapshotDate(any(), eq(TODAY))).thenReturn(false);
        when(fundService.computeFundValuation(1L))
                .thenReturn(new InvestmentFundService.FundValuation(
                        new BigDecimal("100000.0000"), new BigDecimal("5000.0000")));
        when(fundService.computeFundValuation(2L))
                .thenReturn(new InvestmentFundService.FundValuation(
                        new BigDecimal("200000.0000"), new BigDecimal("-1000.0000")));

        scheduler().captureMonthlySnapshots();

        ArgumentCaptor<FundValueSnapshot> captor = ArgumentCaptor.forClass(FundValueSnapshot.class);
        verify(snapshotRepository, times(2)).save(captor.capture());
        List<FundValueSnapshot> saved = captor.getAllValues();

        assertThat(saved).extracting(FundValueSnapshot::getFundId).containsExactlyInAnyOrder(1L, 2L);
        assertThat(saved).allMatch(s -> s.getSnapshotDate().equals(TODAY));
        FundValueSnapshot s1 = saved.stream().filter(s -> s.getFundId().equals(1L)).findFirst().orElseThrow();
        assertThat(s1.getTotalValue()).isEqualByComparingTo("100000.0000");
        assertThat(s1.getProfit()).isEqualByComparingTo("5000.0000");
    }

    @Test
    void isIdempotent_skipsFundThatAlreadyHasSnapshotForToday() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(fund(1L), fund(2L)));
        // fund 1 already snapshotted today, fund 2 not
        when(snapshotRepository.existsByFundIdAndSnapshotDate(1L, TODAY)).thenReturn(true);
        when(snapshotRepository.existsByFundIdAndSnapshotDate(2L, TODAY)).thenReturn(false);
        when(fundService.computeFundValuation(2L))
                .thenReturn(new InvestmentFundService.FundValuation(
                        new BigDecimal("200000.0000"), BigDecimal.ZERO));

        scheduler().captureMonthlySnapshots();

        ArgumentCaptor<FundValueSnapshot> captor = ArgumentCaptor.forClass(FundValueSnapshot.class);
        verify(snapshotRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFundId()).isEqualTo(2L);
        // valuation never computed for the skipped fund
        verify(fundService, never()).computeFundValuation(1L);
    }

    @Test
    void doesNothing_whenNoFunds() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc()).thenReturn(List.of());

        scheduler().captureMonthlySnapshots();

        verify(snapshotRepository, never()).save(any());
    }

    @Test
    void oneFailingFundDoesNotAbortTheRest() {
        when(fundRepository.findByDeletedFalseOrderByNazivAsc())
                .thenReturn(List.of(fund(1L), fund(2L)));
        when(snapshotRepository.existsByFundIdAndSnapshotDate(any(), eq(TODAY))).thenReturn(false);
        when(fundService.computeFundValuation(1L))
                .thenThrow(new IllegalStateException("market-service down"));
        when(fundService.computeFundValuation(2L))
                .thenReturn(new InvestmentFundService.FundValuation(
                        new BigDecimal("200000.0000"), BigDecimal.ZERO));

        scheduler().captureMonthlySnapshots();

        // fund 2 still snapshotted despite fund 1 throwing
        ArgumentCaptor<FundValueSnapshot> captor = ArgumentCaptor.forClass(FundValueSnapshot.class);
        verify(snapshotRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getFundId()).isEqualTo(2L);
    }
}
