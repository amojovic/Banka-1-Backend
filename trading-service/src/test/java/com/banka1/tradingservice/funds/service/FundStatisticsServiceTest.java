package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.dto.FundStatisticsDto;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * WP-18 (Celina 4.4): unit testovi za {@link FundStatisticsService}.
 *
 * <p>Korektnost-kriticno: metrike su zakucane na poznatu seriju sa rucno
 * izracunatim vrednostima (visoka preciznost, zaokruzeno na 8 decimala HALF_UP).
 *
 * <p>Referentne serije i ocekivane vrednosti:
 * <ul>
 *   <li><b>A</b> {@code [100000,110000,121000,133100]} — svaki mesec +10%:
 *       prinosi {@code [0.1,0.1,0.1]}, mean 0.1, volatilnost 0 -> rv null,
 *       godisnji {@code 1.1^12-1 = 2.13842838}, drawdown 0.</li>
 *   <li><b>B</b> {@code [100,120,90,135]} — prinosi {@code [0.20,-0.25,0.50]},
 *       mean 0.15, volatilnost {@code sqrt(0.1425) = 0.37749172},
 *       rv {@code 0.15/0.3774917... = 0.39735971}, godisnji
 *       {@code 1.35^4-1 = 2.32150625}, drawdown {@code (120-90)/120 = 0.25}.</li>
 *   <li><b>E</b> {@code [200,210,205,230,250]} — volatilnost 0.06240362,
 *       rv 0.94184520, godisnji 0.95312500, drawdown {@code (210-205)/210 = 0.02380952}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class FundStatisticsServiceTest {

    @Mock
    private FundValueSnapshotRepository snapshotRepository;

    /** Servis sa default min-snapshots (3). */
    private FundStatisticsService service() {
        return new FundStatisticsService(snapshotRepository, FundStatisticsService.DEFAULT_MIN_SNAPSHOTS_FOR_STATS);
    }

    private static List<BigDecimal> series(String... values) {
        return IntStream.range(0, values.length)
                .mapToObj(i -> new BigDecimal(values[i]))
                .toList();
    }

    private List<FundValueSnapshot> snapshots(Long fundId, String... totalValues) {
        return IntStream.range(0, totalValues.length)
                .mapToObj(i -> FundValueSnapshot.builder()
                        .fundId(fundId)
                        .snapshotDate(LocalDate.of(2026, 1, 1).plusMonths(i))
                        .totalValue(new BigDecimal(totalValues[i]))
                        .profit(BigDecimal.ZERO)
                        .build())
                .toList();
    }

    // ----------------------- known-series correctness -----------------------

    @Test
    void seriesA_constantTenPercentGrowth_volatilityZero_rewardNull() {
        FundStatisticsDto dto = service().computeFromSeries(
                1L, series("100000", "110000", "121000", "133100"));

        assertThat(dto.isMetricsAvailable()).isTrue();
        assertThat(dto.getSnapshotCount()).isEqualTo(4);
        assertThat(dto.getVolatility()).isEqualByComparingTo("0.00000000");
        assertThat(dto.getRewardToVariabilityRatio())
                .as("nulta volatilnost -> rv null (bez deljenja nulom)")
                .isNull();
        assertThat(dto.getAnnualizedReturn()).isEqualByComparingTo("2.13842838");
        assertThat(dto.getMaxDrawdown())
                .as("monotono rastuca serija -> drawdown 0")
                .isEqualByComparingTo("0.00000000");
    }

    @Test
    void seriesB_mixedReturns_pinnedMetrics() {
        FundStatisticsDto dto = service().computeFromSeries(2L, series("100", "120", "90", "135"));

        assertThat(dto.isMetricsAvailable()).isTrue();
        assertThat(dto.getVolatility()).isEqualByComparingTo("0.37749172");
        assertThat(dto.getRewardToVariabilityRatio()).isEqualByComparingTo("0.39735971");
        assertThat(dto.getAnnualizedReturn()).isEqualByComparingTo("2.32150625");
        assertThat(dto.getMaxDrawdown()).isEqualByComparingTo("0.25000000");
    }

    @Test
    void seriesE_fiveSnapshots_pinnedMetrics() {
        FundStatisticsDto dto = service().computeFromSeries(
                3L, series("200", "210", "205", "230", "250"));

        assertThat(dto.isMetricsAvailable()).isTrue();
        assertThat(dto.getSnapshotCount()).isEqualTo(5);
        assertThat(dto.getVolatility()).isEqualByComparingTo("0.06240362");
        assertThat(dto.getRewardToVariabilityRatio()).isEqualByComparingTo("0.94184520");
        assertThat(dto.getAnnualizedReturn()).isEqualByComparingTo("0.95312500");
        assertThat(dto.getMaxDrawdown()).isEqualByComparingTo("0.02380952");
    }

    @Test
    void exactlyMinimumSnapshots_threeSnapshots_computesMetrics() {
        // 3 snapshots == DEFAULT_MIN_SNAPSHOTS_FOR_STATS -> metrics available
        FundStatisticsDto dto = service().computeFromSeries(4L, series("100", "110", "121"));

        assertThat(dto.isMetricsAvailable()).isTrue();
        assertThat(dto.getSnapshotCount()).isEqualTo(3);
        // returns [0.1, 0.1] -> volatility 0 -> rv null
        assertThat(dto.getVolatility()).isEqualByComparingTo("0.00000000");
        assertThat(dto.getRewardToVariabilityRatio()).isNull();
        assertThat(dto.getAnnualizedReturn()).isEqualByComparingTo("2.13842838");
        assertThat(dto.getMaxDrawdown()).isEqualByComparingTo("0.00000000");
    }

    // ----------------------- minimum-snapshot gate -----------------------

    @Test
    void belowMinimum_twoSnapshots_metricsUnavailableAndNull() {
        FundStatisticsDto dto = service().computeFromSeries(5L, series("100", "110"));

        assertThat(dto.isMetricsAvailable()).isFalse();
        assertThat(dto.getSnapshotCount()).isEqualTo(2);
        assertThat(dto.getMinSnapshotsRequired()).isEqualTo(3);
        assertThat(dto.getAnnualizedReturn()).isNull();
        assertThat(dto.getVolatility()).isNull();
        assertThat(dto.getMaxDrawdown()).isNull();
        assertThat(dto.getRewardToVariabilityRatio()).isNull();
    }

    @Test
    void belowMinimum_emptySeries_metricsUnavailable() {
        FundStatisticsDto dto = service().computeFromSeries(6L, List.of());

        assertThat(dto.isMetricsAvailable()).isFalse();
        assertThat(dto.getSnapshotCount()).isZero();
    }

    @Test
    void belowMinimum_singleSnapshot_metricsUnavailable() {
        FundStatisticsDto dto = service().computeFromSeries(7L, series("100000"));

        assertThat(dto.isMetricsAvailable()).isFalse();
        assertThat(dto.getSnapshotCount()).isEqualTo(1);
    }

    @Test
    void configurableMinimum_higherThreshold_gatesOtherwiseValidSeries() {
        FundStatisticsService strict = new FundStatisticsService(snapshotRepository, 5);
        // 4 snapshots, valid math, but threshold is 5 -> unavailable
        FundStatisticsDto dto = strict.computeFromSeries(8L, series("100", "120", "90", "135"));

        assertThat(dto.isMetricsAvailable()).isFalse();
        assertThat(dto.getMinSnapshotsRequired()).isEqualTo(5);
    }

    @Test
    void minimumIsFlooredAtTwo_evenWhenConfiguredLower() {
        // configuring 1 (or 0) is nonsensical for a sample std dev -> floored to 2
        FundStatisticsService loose = new FundStatisticsService(snapshotRepository, 1);
        assertThat(loose.getMinSnapshotsForStats()).isEqualTo(2);
    }

    // ----------------------- degenerate series -----------------------

    @Test
    void zeroValueInSeries_metricsUnavailable_noDivideByZero() {
        // v[1] == 0 -> r[2] would divide by zero -> metrics unavailable
        FundStatisticsDto dto = service().computeFromSeries(9L, series("100", "0", "50"));

        assertThat(dto.isMetricsAvailable()).isFalse();
        assertThat(dto.getSnapshotCount()).isEqualTo(3);
    }

    // ----------------------- repository-backed entry point -----------------------

    @Test
    void computeStatistics_readsSeriesFromRepository() {
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(42L))
                .thenReturn(snapshots(42L, "100", "120", "90", "135"));

        FundStatisticsDto dto = service().computeStatistics(42L);

        assertThat(dto.getFundId()).isEqualTo(42L);
        assertThat(dto.isMetricsAvailable()).isTrue();
        assertThat(dto.getVolatility()).isEqualByComparingTo("0.37749172");
        assertThat(dto.getMaxDrawdown()).isEqualByComparingTo("0.25000000");
    }

    @Test
    void computeStatistics_belowMinimum_whenRepositoryHasTooFewRows() {
        when(snapshotRepository.findByFundIdOrderBySnapshotDateAsc(43L))
                .thenReturn(snapshots(43L, "100", "110"));

        FundStatisticsDto dto = service().computeStatistics(43L);

        assertThat(dto.getFundId()).isEqualTo(43L);
        assertThat(dto.isMetricsAvailable()).isFalse();
    }
}
