package com.banka1.transfer.client.impl;

import com.banka1.transfer.client.AccountClient;
import com.banka1.transfer.dto.client.AccountDto;
import com.banka1.transfer.dto.client.PaymentDto;
import com.banka1.transfer.dto.client.UpdatedBalanceResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Implementacija klijenta za Account Service koja koristi RestClient.
 * Aktivna samo u profilima koji nisu "local".
 */
@Component
@Profile("!local")
@RequiredArgsConstructor
public class AccountClientImpl implements AccountClient {

    private final RestClient accountRestClient;

    @Override
    public AccountDto getAccountDetails(String accountNumber) {
        return accountRestClient.get()
                .uri("/api/accounts/{accountNumber}", accountNumber)
                .retrieve()
                .body(AccountDto.class);
    }

    @Override
    public UpdatedBalanceResponseDto executeTransfer(PaymentDto paymentDto) {
        return accountRestClient.post()
                .uri("/internal/accounts/transfer") // Ruta sa novog kontrolera
                .body(paymentDto)
                .retrieve()
                .body(UpdatedBalanceResponseDto.class);
    }
}
