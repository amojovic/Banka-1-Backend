package com.banka1.transfer.client.impl;

import com.banka1.transfer.client.ExchangeClient;
import com.banka1.transfer.dto.client.ExchangeResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;


/**
 * Implementacija klijenta za menjačnicu koja koristi query parametre za kalkulaciju kursa.
 */
@Component
@Profile("!local")
@RequiredArgsConstructor
public class ExchangeClientImpl implements ExchangeClient {

    private final RestClient exchangeRestClient;

    @Override
    public ExchangeResponseDto calculateExchange(String fromCurrency, String toCurrency, BigDecimal amount) {
        return exchangeRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/exchange/calculate")
                        .queryParam("fromCurrency", fromCurrency)
                        .queryParam("toCurrency", toCurrency)
                        .queryParam("amount", amount)
                        .build())
                .retrieve()
                .body(ExchangeResponseDto.class);
    }
}