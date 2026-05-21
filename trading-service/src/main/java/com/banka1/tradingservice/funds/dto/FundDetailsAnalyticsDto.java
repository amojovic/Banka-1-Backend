package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class FundDetailsAnalyticsDto {
    private InvestmentFundDto fund;
    private FundMetricValuesDto metrics;
    private List<FundValueSnapshotPointDto> historicalValuePoints;
    private List<FundPerformanceComparisonPointDto> averageFundPerformancePoints;
}
