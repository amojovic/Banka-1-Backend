package com.banka1.account_service.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request payload for a one-sided trade-leg debit/credit operation that
 * targets a single account. Used by the order-service to settle the
 * <em>trade leg</em> of a BUY or SELL exchange order.
 *
 * <p>Trade-leg funds for a non-bank account are deliberately not routed
 * through a paired transfer to a bank account: the matching exchange-side
 * counter-leg is external to the system, so a paired transfer would either
 * spuriously credit/debit the bank or fail same-owner validation. Instead,
 * the trade amount is debited (BUY) or credited (SELL) on the targeted
 * account directly, while the regulatory commission continues to flow
 * through the existing paired transfer path.
 *
 * <p>The endpoint accepts either {@code accountNumber} or {@code accountId}
 * to keep older API clients working while migrating from the historic
 * hash-derived numeric identifier to the persistent primary key.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OneSidedTransactionDto {

    /** 18-digit account number; service accepts either this or {@link #accountId}. */
    private String accountNumber;

    /** Database primary key of the account. Alternative to {@link #accountNumber}. */
    private Long accountId;

    /** Amount to debit/credit. Must be strictly positive. */
    private BigDecimal amount;

    /** Initiating party identifier (audit/log only). */
    private Long clientId;

    /** Free-form description for audit logs. */
    private String description;
}
