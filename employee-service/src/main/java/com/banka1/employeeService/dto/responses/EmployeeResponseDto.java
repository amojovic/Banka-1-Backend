package com.banka1.employeeService.dto.responses;

import com.banka1.employeeService.domain.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji predstavlja podatke o zaposlenom koji se vracaju klijentu.
 * Ne sadrzi osetljive podatke poput lozinke ili tokena.
 */
@Getter
@Setter
@AllArgsConstructor
public class EmployeeResponseDto {

    /** Identifikator zaposlenog. */
    private Long id;

    /** Ime zaposlenog. */
    private String ime;

    /** Prezime zaposlenog. */
    private String prezime;

    /** Email adresa zaposlenog. */
    private String email;

    /** Korisnicko ime zaposlenog. */
    private String username;

    /** Pozicija (radno mesto) zaposlenog. */
    private String pozicija;

    /** Departman u kome zaposleni radi. */
    private String departman;

    /** Indikator da li je nalog zaposlenog aktivan. */
    private boolean aktivan;

    /** RBAC uloga zaposlenog. */
    private Role role;
}
