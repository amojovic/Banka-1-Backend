package com.banka1.clientService.service;

import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientIdResponseDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Servis za CRUD operacije nad entitetom klijenta.
 * Pokriva kreiranje, pretragu, azuriranje i soft-brisanje klijenata.
 */
public interface ClientService {

    /**
     * Kreira novog klijenta.
     *
     * @param dto podaci za kreiranje klijenta
     * @return kreirani klijent
     */
    ClientResponseDto createClient(ClientCreateRequestDto dto);

    /**
     * Pretrazuje klijente po kombinaciji filtera uz paginaciju.
     * Sortirano abecedno po prezimenu.
     *
     * @param ime filter po imenu (null = wildcard)
     * @param prezime filter po prezimenu (null = wildcard)
     * @param email filter po email adresi (null = wildcard)
     * @param pageable parametri paginacije i sortiranja
     * @return stranica klijenata koji odgovaraju filterima
     */
    Page<ClientResponseDto> searchClients(String ime, String prezime, String email, Pageable pageable);

    /**
     * Vrsi globalnu tekstualnu pretragu klijenata po imenu, prezimenu i emailu.
     *
     * @param query tekstualni upit
     * @param pageable parametri paginacije
     * @return stranica rezultata pretrage
     */
    Page<ClientResponseDto> globalSearchClients(String query, Pageable pageable);

    /**
     * Azurira podatke klijenta po identifikatoru.
     * JMBG i password se ne mogu menjati.
     *
     * @param id identifikator klijenta koji se menja
     * @param dto podaci za azuriranje
     * @return azurirani klijent
     */
    ClientResponseDto updateClient(Long id, ClientUpdateRequestDto dto);

    /**
     * Soft-brise klijenta po identifikatoru.
     *
     * @param id identifikator klijenta koji se brise
     */
    void deleteClient(Long id);

    /**
     * Vraca ID klijenta na osnovu JMBG-a.
     * Endpoint dostupan samo SERVICE tokenima.
     *
     * @param jmbg JMBG klijenta
     * @return DTO sa ID-em klijenta
     */
    ClientIdResponseDto getIdByJmbg(String jmbg);
}
