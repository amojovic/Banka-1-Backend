package com.banka1.transfer.client.impl;

import com.banka1.transfer.client.ClientClient;
import com.banka1.transfer.dto.client.ClientInfoResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Implementacija klijenta za komunikaciju sa servisom klijenata putem REST API-ja.
 */
@Component
@Profile("!local") // Učitava se kad god profil nije "local"
@RequiredArgsConstructor
public class ClientClientImpl implements ClientClient {

    private final RestClient clientRestClient;

    @Override
    public ClientInfoResponseDto getClientDetails(Long clientId) {
        return clientRestClient.get()
                .uri("/customers/{id}", clientId) // fixme, proveriti da li je ovo dobra ruta
                .retrieve()
                .body(ClientInfoResponseDto.class);
    }
}
