package com.banka1.account_service.service;

import com.banka1.account_service.dto.request.PaymentDto;

import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import org.springframework.security.oauth2.jwt.Jwt;


public interface AccountService {
    UpdatedBalanceResponseDto transaction(PaymentDto paymentDto);
    UpdatedBalanceResponseDto transfer(PaymentDto paymentDto);
}
