package com.banka1.clientService.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji vraca samo ID klijenta, koristi se za JMBG lookup endpoint.
 */
@Getter
@Setter
@AllArgsConstructor
public class ClientIdResponseDto {

    /** Identifikator klijenta. */
    private Long id;
}
