package com.banka1.tradingservice.funds.dto;

import com.banka1.tradingservice.funds.domain.FundDividendPayoutStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class FundDividendPayoutDto {
    private Long clientId;
    private String clientAccountNumber;
    private BigDecimal ownershipRatio;
    private BigDecimal amountRsd;
    private FundDividendPayoutStatus status;
    private String failureReason;
}
