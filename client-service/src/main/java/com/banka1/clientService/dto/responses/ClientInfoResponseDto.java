package com.banka1.clientService.dto.responses;

import com.banka1.clientService.domain.enums.ClientRole;
import com.banka1.clientService.domain.enums.Pol;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji vraca sve relevantne informacije o klijentu, koristi se za SERVICE/ADMIN lookup endpointe.
 */
@Getter
@Setter
@AllArgsConstructor
public class ClientInfoResponseDto {

    private Long id;
    private String name;
    private String lastName;
    private String email;
    private String jmbg;
    private String phoneNumber;
    private String address;
    private Pol gender;
    private Long dateOfBirth;
    private ClientRole role;
    private boolean active;
}
