package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.dto.FundMetricValuesDto;
import com.banka1.tradingservice.funds.dto.FundSortField;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FundStatisticsService {

    static final int MIN_MONTHLY_SNAPSHOTS = 12;
    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FundValueSnapshotService snapshotService;

    public FundMetricValuesDto metricsFor(Long fundId) {
        return metricsFromSnapshots(snapshotService.monthlySnapshots(fundId));
    }

    public List<InvestmentFundDto> sort(List<InvestmentFundDto> funds, FundSortField sortField, Sort.Direction sortDirection) {
        Comparator<BigDecimal> moneyComparator = sortDirection == Sort.Direction.DESC
                ? Comparator.nullsLast(Comparator.reverseOrder())
                : Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<InvestmentFundDto> comparator = switch (sortField) {
            case TOTAL_VALUE -> Comparator.comparing(InvestmentFundDto::getTotalValue, moneyComparator);
            case PROFIT -> Comparator.comparing(InvestmentFundDto::getProfit, moneyComparator);
            case ANNUALIZED_RETURN -> Comparator.comparing(InvestmentFundDto::getAnnualizedReturn, moneyComparator);
            case REWARD_TO_VARIABILITY_RATIO -> Comparator.comparing(
                    InvestmentFundDto::getRewardToVariabilityRatio, moneyComparator);
            case MAX_DRAWDOWN -> Comparator.comparing(InvestmentFundDto::getMaxDrawdown, moneyComparator);
            case VOLATILITY -> Comparator.comparing(InvestmentFundDto::getVolatility, moneyComparator);
            case NAME -> Comparator.comparing(InvestmentFundDto::getNaziv, String.CASE_INSENSITIVE_ORDER);
        };
        if (sortDirection == Sort.Direction.DESC && sortField == FundSortField.NAME) {
            comparator = comparator.reversed();
        }
        return funds.stream()
                .sorted(comparator.thenComparing(InvestmentFundDto::getNaziv, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    FundMetricValuesDto metricsFromSnapshots(List<FundValueSnapshot> monthlySnapshots) {
        if (monthlySnapshots == null || monthlySnapshots.size() < MIN_MONTHLY_SNAPSHOTS) {
            return FundMetricValuesDto.builder()
                    .monthlySnapshotsUsed(monthlySnapshots == null ? 0 : monthlySnapshots.size())
                    .annualizedReturn(null)
                    .rewardToVariabilityRatio(null)
                    .maxDrawdown(null)
                    .volatility(null)
                    .build();
        }

        List<BigDecimal> values = monthlySnapshots.stream().map(FundValueSnapshot::getTotalValue).toList();
        BigDecimal annualizedReturn = calculateAnnualizedReturn(values);
        BigDecimal volatility = calculateVolatility(values);
        BigDecimal rewardRatio = (annualizedReturn == null || volatility == null || volatility.signum() == 0)
                ? null
                : annualizedReturn.divide(volatility, 6, RoundingMode.HALF_UP);
        BigDecimal maxDrawdown = calculateMaxDrawdown(values);

        return FundMetricValuesDto.builder()
                .monthlySnapshotsUsed(monthlySnapshots.size())
                .annualizedReturn(scaleOrNull(annualizedReturn))
                .rewardToVariabilityRatio(scaleOrNull(rewardRatio))
                .maxDrawdown(scaleOrNull(maxDrawdown))
                .volatility(scaleOrNull(volatility))
                .build();
    }

    private BigDecimal calculateAnnualizedReturn(List<BigDecimal> values) {
        BigDecimal start = values.get(0);
        BigDecimal end = values.get(values.size() - 1);
        if (start == null || end == null || start.signum() <= 0) {
            return null;
        }
        double ratio = end.divide(start, MC).doubleValue();
        double years = (double) (values.size() - 1) / 12.0d;
        if (years <= 0d) {
            return null;
        }
        return BigDecimal.valueOf(Math.pow(ratio, 1.0d / years) - 1.0d).multiply(HUNDRED);
    }

    private BigDecimal calculateVolatility(List<BigDecimal> values) {
        List<BigDecimal> returns = monthlyReturns(values);
        if (returns.isEmpty()) {
            return null;
        }
        double mean = returns.stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0d);
        double variance = returns.stream()
                .mapToDouble(value -> Math.pow(value.doubleValue() - mean, 2))
                .average()
                .orElse(0d);
        return BigDecimal.valueOf(Math.sqrt(variance)).multiply(HUNDRED);
    }

    private BigDecimal calculateMaxDrawdown(List<BigDecimal> values) {
        BigDecimal peak = values.get(0);
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal value : values) {
            if (value.compareTo(peak) > 0) {
                peak = value;
            }
            if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(value)
                        .divide(peak, 8, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
                if (drawdown.compareTo(maxDrawdown) > 0) {
                    maxDrawdown = drawdown;
                }
            }
        }
        return maxDrawdown;
    }

    private List<BigDecimal> monthlyReturns(List<BigDecimal> values) {
        List<BigDecimal> returns = new ArrayList<>();
        for (int i = 1; i < values.size(); i++) {
            BigDecimal previous = values.get(i - 1);
            BigDecimal current = values.get(i);
            if (previous == null || current == null || previous.signum() <= 0) {
                continue;
            }
            returns.add(current.subtract(previous)
                    .divide(previous, 8, RoundingMode.HALF_UP));
        }
        return returns;
    }

    private BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }
}
