package com.banka1.userService.dto.responses;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji se vraca klijentu nakon uspesne provere aktivacionog tokena.
 * Sadrzi identifikator {@code ConfirmationToken} entiteta potreban za naredni korak (postavljanje lozinke).
 */
@Getter
@Setter
@AllArgsConstructor
public class CheckActivateDto {

    /** Identifikator potvrde koja se koristi u zahtevu za aktivaciju ili reset lozinke. */
    private Long idConfirmationTokena;
}
