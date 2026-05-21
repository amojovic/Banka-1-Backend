package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.dto.FundPerformanceComparisonPointDto;
import com.banka1.tradingservice.funds.dto.FundValueSnapshotPointDto;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FundValueSnapshotService {

    private final FundValueSnapshotRepository snapshotRepository;
    private final InvestmentFundRepository fundRepository;
    private final FundHoldingService fundHoldingService;

    @Transactional
    public FundValueSnapshot recordSnapshot(Long fundId, LocalDate snapshotDate) {
        InvestmentFund fund = fundRepository.findById(fundId)
                .orElseThrow(() -> new IllegalArgumentException("Fond " + fundId + " ne postoji."));
        BigDecimal liquidity = safeMoney(fund.getLikvidnaSredstva());
        BigDecimal holdings = safeMoney(fundHoldingService.calculateHoldingsValue(fundId));
        BigDecimal total = liquidity.add(holdings).setScale(2, RoundingMode.HALF_UP);

        FundValueSnapshot snapshot = snapshotRepository.findByFundIdAndSnapshotDate(fundId, snapshotDate)
                .orElseGet(FundValueSnapshot::new);
        snapshot.setFundId(fundId);
        snapshot.setSnapshotDate(snapshotDate);
        snapshot.setLiquidityValue(liquidity);
        snapshot.setHoldingsValue(holdings);
        snapshot.setTotalValue(total);
        return snapshotRepository.save(snapshot);
    }

    @Transactional(readOnly = true)
    public List<FundValueSnapshotPointDto> history(Long fundId) {
        return snapshotRepository.findByFundIdOrderBySnapshotDateAsc(fundId).stream()
                .map(snapshot -> FundValueSnapshotPointDto.builder()
                        .snapshotDate(snapshot.getSnapshotDate())
                        .liquidityValue(snapshot.getLiquidityValue())
                        .holdingsValue(snapshot.getHoldingsValue())
                        .totalValue(snapshot.getTotalValue())
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FundValueSnapshot> monthlySnapshots(Long fundId) {
        Map<YearMonth, FundValueSnapshot> lastPerMonth = new LinkedHashMap<>();
        for (FundValueSnapshot snapshot : snapshotRepository.findByFundIdOrderBySnapshotDateAsc(fundId)) {
            lastPerMonth.put(YearMonth.from(snapshot.getSnapshotDate()), snapshot);
        }
        return lastPerMonth.values().stream()
                .sorted(Comparator.comparing(FundValueSnapshot::getSnapshotDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FundPerformanceComparisonPointDto> averagePerformance(Long fundId) {
        List<FundValueSnapshot> baseFund = snapshotRepository.findByFundIdOrderBySnapshotDateAsc(fundId);
        if (baseFund.isEmpty()) {
            return List.of();
        }
        BigDecimal baseFundValue = baseFund.get(0).getTotalValue();
        if (baseFundValue == null || baseFundValue.signum() <= 0) {
            return List.of();
        }

        List<InvestmentFund> funds = fundRepository.findByDeletedFalseOrderByNazivAsc();
        Map<Long, List<FundValueSnapshot>> allSnapshots = new LinkedHashMap<>();
        for (InvestmentFund fund : funds) {
            allSnapshots.put(fund.getId(), snapshotRepository.findByFundIdOrderBySnapshotDateAsc(fund.getId()));
        }

        List<LocalDate> dates = baseFund.stream().map(FundValueSnapshot::getSnapshotDate).toList();
        return dates.stream().map(date -> {
            BigDecimal fundIndex = indexForDate(baseFund, date);
            BigDecimal sum = BigDecimal.ZERO;
            int count = 0;
            for (List<FundValueSnapshot> snapshots : allSnapshots.values()) {
                BigDecimal idx = indexForDate(snapshots, date);
                if (idx != null) {
                    sum = sum.add(idx);
                    count++;
                }
            }
            BigDecimal average = count == 0 ? null
                    : sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
            return FundPerformanceComparisonPointDto.builder()
                    .snapshotDate(date)
                    .fundPerformanceIndex(fundIndex)
                    .averagePerformanceIndex(average)
                    .build();
        }).toList();
    }

    @Scheduled(cron = "${fund.snapshot.cron:0 10 0 * * *}")
    @Transactional
    public void captureDailySnapshots() {
        LocalDate today = LocalDate.now();
        for (InvestmentFund fund : fundRepository.findByDeletedFalseOrderByNazivAsc()) {
            recordSnapshot(fund.getId(), today);
        }
        log.debug("Captured daily fund snapshots for {}", today);
    }

    private BigDecimal indexForDate(List<FundValueSnapshot> snapshots, LocalDate date) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        BigDecimal base = snapshots.get(0).getTotalValue();
        if (base == null || base.signum() <= 0) {
            return null;
        }
        return snapshots.stream()
                .filter(snapshot -> snapshot.getSnapshotDate().equals(date))
                .findFirst()
                .map(snapshot -> snapshot.getTotalValue()
                        .divide(base, 4, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100")))
                .orElse(null);
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }
}
