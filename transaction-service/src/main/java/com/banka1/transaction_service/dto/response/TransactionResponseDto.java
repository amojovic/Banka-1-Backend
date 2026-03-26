package com.banka1.transaction_service.dto.response;

import com.banka1.transaction_service.domain.Payment;
import com.banka1.transaction_service.domain.enums.CurrencyCode;
import com.banka1.transaction_service.domain.enums.TransactionStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class TransactionResponseDto {
    private String orderNumber;
    private String fromAccountNumber;
    private String toAccountNumber;
    private BigDecimal initialAmount;
    private BigDecimal finalAmount;
    private String recipientName;
    private String paymentCode;
    private String referenceNumber;
    private String paymentPurpose;
    private TransactionStatus status;
    private CurrencyCode fromCurrency;
    private  CurrencyCode toCurrency;
    private BigDecimal exchangeRate;
    private LocalDateTime createdAt;

    public TransactionResponseDto(Payment payment) {
        this.orderNumber = payment.getOrderNumber();
        this.fromAccountNumber = payment.getFromAccountNumber();
        this.toAccountNumber = payment.getToAccountNumber();
        this.initialAmount = payment.getInitialAmount();
        this.finalAmount = payment.getFinalAmount();
        this.recipientName = payment.getRecipientName();
        this.paymentCode = payment.getPaymentCode();
        this.referenceNumber = payment.getReferenceNumber();
        this.paymentPurpose = payment.getPaymentPurpose();
        this.status = payment.getStatus();
        this.fromCurrency = payment.getFromCurrency();
        this.toCurrency = payment.getToCurrency();
        this.exchangeRate = payment.getExchangeRate();
        this.createdAt = payment.getCreatedAt();
    }
}
