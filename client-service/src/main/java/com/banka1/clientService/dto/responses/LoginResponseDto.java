package com.banka1.clientService.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * DTO koji se vraca klijentu nakon uspesne autentifikacije.
 */
@Getter
@AllArgsConstructor
public class LoginResponseDto {

    /** JWT pristupni token za dalju autentifikaciju zahteva. */
    private String token;

    private Long id;
    private String ime;
    private String prezime;
    private String email;
}
