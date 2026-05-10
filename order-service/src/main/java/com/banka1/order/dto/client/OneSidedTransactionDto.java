package com.banka1.order.dto.client;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Order-service mirror of the account-service request DTO for a one-sided
 * trade-leg debit/credit operation. Sent to {@code /internal/accounts/exchange/buy}
 * (debit) or {@code /internal/accounts/exchange/sell} (credit) when settling
 * the funds leg of an executed exchange order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OneSidedTransactionDto {
    private String accountNumber;
    private Long accountId;
    private BigDecimal amount;
    private Long clientId;
    private String description;
}
