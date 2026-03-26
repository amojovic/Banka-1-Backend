package com.banka1.transfer.client;

import com.banka1.transfer.dto.client.ClientInfoResponseDto;

/**
 * Interfejs za komunikaciju sa servisom za klijente i korisnike (Client/User Service).
 */
public interface ClientClient {
    /**
     * Dobavlja osnovne informacije o klijentu (ime, prezime, email) na osnovu njegovog ID-a.
     * @param clientId jedinstveni identifikator klijenta
     * @return DTO sa kontakt informacijama klijenta
     */
    ClientInfoResponseDto getClientDetails(Long clientId);
}
