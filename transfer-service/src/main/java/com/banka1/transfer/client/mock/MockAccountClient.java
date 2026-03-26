package com.banka1.transfer.client.mock;

import com.banka1.transfer.client.AccountClient;
import com.banka1.transfer.dto.client.AccountDto;
import com.banka1.transfer.dto.client.PaymentDto;
import com.banka1.transfer.dto.client.UpdatedBalanceResponseDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Slf4j
@Component
@Profile("local")
public class MockAccountClient implements AccountClient {
    @Override
    public AccountDto getAccountDetails(String accountNumber) {
        return new AccountDto(accountNumber, 1L, "EUR", new BigDecimal("10000.00"), "ACTIVE", "LIČNI");
    }

    @Override
    public UpdatedBalanceResponseDto executeTransfer(PaymentDto paymentDto) {
        log.info("MOCK: Executing atomic transfer for client {}", paymentDto.clientId());
        return new UpdatedBalanceResponseDto(new BigDecimal("9000.00"), new BigDecimal("500.00"));
    }
}
