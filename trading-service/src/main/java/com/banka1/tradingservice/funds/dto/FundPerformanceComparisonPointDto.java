package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class FundPerformanceComparisonPointDto {
    private LocalDate snapshotDate;
    private BigDecimal fundPerformanceIndex;
    private BigDecimal averagePerformanceIndex;
}
