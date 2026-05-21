package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FundMetricValuesDto {
    private Integer monthlySnapshotsUsed;
    private BigDecimal annualizedReturn;
    private BigDecimal rewardToVariabilityRatio;
    private BigDecimal maxDrawdown;
    private BigDecimal volatility;
}
