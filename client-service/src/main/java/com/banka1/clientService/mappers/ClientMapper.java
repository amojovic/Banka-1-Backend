package com.banka1.clientService.mappers;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import org.springframework.stereotype.Component;

/**
 * Mapper koji konvertuje DTO objekte u JPA entitete i obrnuto za entitet {@link Klijent}.
 */
@Component
public class ClientMapper {

    /**
     * Mapira DTO za kreiranje klijenta u entitet.
     * Password i salt se ne postavljaju ovde – kreira ih servis ili se postavljaju pri aktivaciji.
     *
     * @param dto ulazni podaci za kreiranje
     * @return novi entitet klijenta
     */
    public Klijent toEntity(ClientCreateRequestDto dto) {
        Klijent klijent = new Klijent();
        klijent.setIme(dto.getIme());
        klijent.setPrezime(dto.getPrezime());
        klijent.setDatumRodjenja(dto.getDatumRodjenja());
        klijent.setPol(dto.getPol());
        klijent.setEmail(dto.getEmail());
        klijent.setBrojTelefona(dto.getBrojTelefona());
        klijent.setAdresa(dto.getAdresa());
        klijent.setJmbg(dto.getJmbg());
        return klijent;
    }

    /**
     * Mapira entitet klijenta u izlazni DTO za API odgovor.
     * Ne ukljucuje osetljive podatke poput lozinke, salta ili JMBG-a.
     *
     * @param klijent entitet klijenta
     * @return DTO za API odgovor
     */
    public ClientResponseDto toDto(Klijent klijent) {
        return new ClientResponseDto(
                klijent.getId(),
                klijent.getIme(),
                klijent.getPrezime(),
                klijent.getDatumRodjenja(),
                klijent.getPol(),
                klijent.getEmail(),
                klijent.getBrojTelefona(),
                klijent.getAdresa()
        );
    }

    /**
     * Azurira entitet klijenta podacima iz DTO-a.
     * JMBG i password se ne mogu menjati ovom metodom.
     * Null vrednosti u DTO-u znace da se to polje ne menja.
     *
     * @param klijent entitet koji se menja
     * @param dto     DTO sa novim vrednostima
     */
    public void updateEntityFromDto(Klijent klijent, ClientUpdateRequestDto dto) {
        if (dto.getIme() != null) klijent.setIme(dto.getIme());
        if (dto.getPrezime() != null) klijent.setPrezime(dto.getPrezime());
        if (dto.getDatumRodjenja() != null) klijent.setDatumRodjenja(dto.getDatumRodjenja());
        if (dto.getPol() != null) klijent.setPol(dto.getPol());
        if (dto.getBrojTelefona() != null) klijent.setBrojTelefona(dto.getBrojTelefona());
        if (dto.getAdresa() != null) klijent.setAdresa(dto.getAdresa());
        // email se postavlja posebno u servisu jer zahteva proveru jedinstvenosti
    }
}
