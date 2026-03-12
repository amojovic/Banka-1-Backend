package com.banka1.userService.dto.requests;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO koji zaposleni koristi za samostalnu izmenu sopstvenih podataka profila.
 * Sva polja su opciona – samo popunjena polja ce biti azurirana.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class EmployeeEditRequestDto {

    /** Novo ime zaposlenog. */
    private String ime;

    /** Novo prezime zaposlenog. */
    private String prezime;

    /** Novi broj telefona zaposlenog. */
    private String brojTelefona;

    /** Nova adresa stanovanja zaposlenog. */
    private String adresa;

    /** Nova pozicija zaposlenog. */
    private String pozicija;

    /** Novi departman zaposlenog. */
    private String departman;
}
