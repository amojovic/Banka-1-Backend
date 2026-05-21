package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.dto.FundSortField;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class FundStatisticsServiceTest {

    @Mock
    private FundValueSnapshotService snapshotService;

    @InjectMocks
    private FundStatisticsService service;

    @Test
    void metricsFromSnapshots_vracaNullKadNemaDovoljnoMesecnihSnapshota() {
        var metrics = service.metricsFromSnapshots(List.of(snapshot(1L, new BigDecimal("100"))));

        assertThat(metrics.getAnnualizedReturn()).isNull();
        assertThat(metrics.getVolatility()).isNull();
    }

    @Test
    void metricsFromSnapshots_racunaSveMetrike() {
        List<FundValueSnapshot> snapshots = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            snapshots.add(snapshot(i + 1L, BigDecimal.valueOf(100 + (i * 5L))));
        }

        var metrics = service.metricsFromSnapshots(snapshots);

        assertThat(metrics.getAnnualizedReturn()).isNotNull();
        assertThat(metrics.getVolatility()).isNotNull();
        assertThat(metrics.getMaxDrawdown()).isNotNull();
    }

    @Test
    void sort_pomeraNullMetrikeNaKraj() {
        InvestmentFundDto a = InvestmentFundDto.builder().naziv("A").annualizedReturn(new BigDecimal("10")).build();
        InvestmentFundDto b = InvestmentFundDto.builder().naziv("B").annualizedReturn(null).build();

        List<InvestmentFundDto> sorted = service.sort(List.of(b, a), FundSortField.ANNUALIZED_RETURN, Sort.Direction.DESC);

        assertThat(sorted.get(0).getNaziv()).isEqualTo("A");
        assertThat(sorted.get(1).getNaziv()).isEqualTo("B");
    }

    private static FundValueSnapshot snapshot(Long id, BigDecimal totalValue) {
        FundValueSnapshot snapshot = new FundValueSnapshot();
        snapshot.setId(id);
        snapshot.setFundId(1L);
        snapshot.setSnapshotDate(LocalDate.of(2025, 1, 1).plusMonths(id - 1));
        snapshot.setLiquidityValue(totalValue);
        snapshot.setHoldingsValue(BigDecimal.ZERO);
        snapshot.setTotalValue(totalValue);
        return snapshot;
    }
}
