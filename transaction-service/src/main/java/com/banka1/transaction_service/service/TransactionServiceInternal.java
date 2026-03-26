package com.banka1.transaction_service.service;

import com.banka1.transaction_service.domain.enums.TransactionStatus;
import com.banka1.transaction_service.dto.request.NewPaymentDto;
import com.banka1.transaction_service.dto.response.ConversionResponseDto;
import com.banka1.transaction_service.dto.response.InfoResponseDto;
import com.banka1.transaction_service.dto.response.UpdatedBalanceResponseDto;
import org.springframework.security.oauth2.jwt.Jwt;

public interface TransactionServiceInternal {
    Long create(Jwt jwt, NewPaymentDto newPaymentDto, InfoResponseDto infoResponseDto, ConversionResponseDto conversionResponseDto);
    void finish(Jwt jwt,InfoResponseDto infoResponseDto, Long id, TransactionStatus transactionStatus);
}
