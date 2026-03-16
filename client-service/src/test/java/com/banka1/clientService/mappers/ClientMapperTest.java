package com.banka1.clientService.mappers;

import com.banka1.clientService.domain.Klijent;
import com.banka1.clientService.domain.enums.Pol;
import com.banka1.clientService.dto.requests.ClientCreateRequestDto;
import com.banka1.clientService.dto.requests.ClientUpdateRequestDto;
import com.banka1.clientService.dto.responses.ClientResponseDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientMapperTest {

    private final ClientMapper mapper = new ClientMapper();

    @Test
    void toEntityMapsAllFieldsFromCreateDto() {
        ClientCreateRequestDto dto = new ClientCreateRequestDto();
        dto.setIme("Petar");
        dto.setPrezime("Petrovic");
        dto.setDatumRodjenja(641520000000L);
        dto.setPol(Pol.M);
        dto.setEmail("petar@banka.com");
        dto.setBrojTelefona("+381641234567");
        dto.setAdresa("Njegoseva 25");
        dto.setJmbg("1234567890123");

        Klijent klijent = mapper.toEntity(dto);

        assertThat(klijent.getIme()).isEqualTo("Petar");
        assertThat(klijent.getPrezime()).isEqualTo("Petrovic");
        assertThat(klijent.getDatumRodjenja()).isEqualTo(641520000000L);
        assertThat(klijent.getPol()).isEqualTo(Pol.M);
        assertThat(klijent.getEmail()).isEqualTo("petar@banka.com");
        assertThat(klijent.getBrojTelefona()).isEqualTo("+381641234567");
        assertThat(klijent.getAdresa()).isEqualTo("Njegoseva 25");
        assertThat(klijent.getJmbg()).isEqualTo("1234567890123");
    }

    @Test
    void toDtoMapsAllFieldsExcludingPasswordAndJmbg() {
        Klijent klijent = new Klijent();
        klijent.setIme("Ana");
        klijent.setPrezime("Anic");
        klijent.setDatumRodjenja(700000000000L);
        klijent.setPol(Pol.Z);
        klijent.setEmail("ana@banka.com");
        klijent.setBrojTelefona("+381601234567");
        klijent.setAdresa("Knez Mihailova 1");
        klijent.setPassword("hashed");
        klijent.setJmbg("9876543210123");

        ClientResponseDto dto = mapper.toDto(klijent);

        assertThat(dto.getIme()).isEqualTo("Ana");
        assertThat(dto.getPrezime()).isEqualTo("Anic");
        assertThat(dto.getDatumRodjenja()).isEqualTo(700000000000L);
        assertThat(dto.getPol()).isEqualTo(Pol.Z);
        assertThat(dto.getEmail()).isEqualTo("ana@banka.com");
        assertThat(dto.getBrojTelefona()).isEqualTo("+381601234567");
        assertThat(dto.getAdresa()).isEqualTo("Knez Mihailova 1");
    }

    @Test
    void updateEntityFromDtoUpdatesOnlyNonNullFields() {
        Klijent klijent = new Klijent();
        klijent.setIme("Staro");
        klijent.setPrezime("StaroPrezime");
        klijent.setDatumRodjenja(641520000000L);
        klijent.setPol(Pol.M);
        klijent.setBrojTelefona("+381640000000");
        klijent.setAdresa("Stara adresa");

        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        dto.setPrezime("NovoPrezime");
        dto.setAdresa("Nova adresa");

        mapper.updateEntityFromDto(klijent, dto);

        assertThat(klijent.getIme()).isEqualTo("Staro");
        assertThat(klijent.getPrezime()).isEqualTo("NovoPrezime");
        assertThat(klijent.getAdresa()).isEqualTo("Nova adresa");
        assertThat(klijent.getBrojTelefona()).isEqualTo("+381640000000");
        assertThat(klijent.getDatumRodjenja()).isEqualTo(641520000000L);
        assertThat(klijent.getPol()).isEqualTo(Pol.M);
    }

    @Test
    void updateEntityFromDtoUpdatesAllFieldsWhenAllProvided() {
        Klijent klijent = new Klijent();
        klijent.setIme("Staro");
        klijent.setPrezime("StaroPrezime");
        klijent.setDatumRodjenja(641520000000L);
        klijent.setPol(Pol.M);
        klijent.setBrojTelefona("+381640000000");
        klijent.setAdresa("Stara adresa");

        ClientUpdateRequestDto dto = new ClientUpdateRequestDto();
        dto.setIme("NovoIme");
        dto.setPrezime("NovoPrezime");
        dto.setDatumRodjenja(700000000000L);
        dto.setPol(Pol.Z);
        dto.setBrojTelefona("+381651111111");
        dto.setAdresa("Nova adresa");

        mapper.updateEntityFromDto(klijent, dto);

        assertThat(klijent.getIme()).isEqualTo("NovoIme");
        assertThat(klijent.getPrezime()).isEqualTo("NovoPrezime");
        assertThat(klijent.getDatumRodjenja()).isEqualTo(700000000000L);
        assertThat(klijent.getPol()).isEqualTo(Pol.Z);
        assertThat(klijent.getBrojTelefona()).isEqualTo("+381651111111");
        assertThat(klijent.getAdresa()).isEqualTo("Nova adresa");
    }

    @Test
    void updateEntityFromDtoDoesNotChangeAnythingWhenAllFieldsNull() {
        Klijent klijent = new Klijent();
        klijent.setIme("Petar");
        klijent.setPrezime("Petrovic");
        klijent.setDatumRodjenja(641520000000L);
        klijent.setPol(Pol.M);
        klijent.setBrojTelefona("+381641234567");
        klijent.setAdresa("Njegoseva 25");

        mapper.updateEntityFromDto(klijent, new ClientUpdateRequestDto());

        assertThat(klijent.getIme()).isEqualTo("Petar");
        assertThat(klijent.getPrezime()).isEqualTo("Petrovic");
        assertThat(klijent.getDatumRodjenja()).isEqualTo(641520000000L);
        assertThat(klijent.getPol()).isEqualTo(Pol.M);
        assertThat(klijent.getBrojTelefona()).isEqualTo("+381641234567");
        assertThat(klijent.getAdresa()).isEqualTo("Njegoseva 25");
    }
}
