package com.banka1.tradingservice.funds.dto;

import com.banka1.tradingservice.funds.domain.FundDividendStrategy;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class RecordFundDividendRequest {

    @NotBlank
    private String stockTicker;

    @NotNull
    @DecimalMin(value = "0.00000001", inclusive = true)
    private BigDecimal dividendPerShare;

    @NotBlank
    private String currency;

    @NotNull
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate paymentDate;

    private FundDividendStrategy strategy;
}
