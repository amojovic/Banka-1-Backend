package com.banka1.tradingservice.funds.dto;

import com.banka1.tradingservice.funds.domain.FundDividendDistributionStatus;
import com.banka1.tradingservice.funds.domain.FundDividendStrategy;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class FundDividendDistributionDto {
    private Long id;
    private Long fundId;
    private String stockTicker;
    private LocalDate paymentDate;
    private BigDecimal dividendPerShare;
    private String sourceCurrency;
    private Integer holdingQuantity;
    private BigDecimal grossAmountSource;
    private BigDecimal grossAmountRsd;
    private FundDividendStrategy strategy;
    private FundDividendDistributionStatus status;
    private Integer reinvestedShares;
    private BigDecimal reinvestedAmountRsd;
    private BigDecimal distributedAmountRsd;
    private String note;
    private LocalDateTime processedAt;
    private List<FundDividendPayoutDto> payouts;
}
