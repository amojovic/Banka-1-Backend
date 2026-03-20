package com.banka1.employeeService.dto.responses;

import com.banka1.employeeService.domain.enums.Permission;
import com.banka1.employeeService.domain.enums.Role;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;

/**
 * DTO koji se vraca klijentu nakon uspesne prijave ili osvezavanja tokena.
 * Sadrzi JWT pristupni token, nehesirani refresh token, ulogu i skup permisija korisnika.
 */
@AllArgsConstructor
@Getter
@Setter
public class TokenResponseDto {

    /** Potpisani JWT pristupni token koji se koristi za autorizaciju API poziva. */
    private String jwt;

    /** Nehesirani refresh token koji se koristi za obnavljanje JWT tokena. */
    private String refreshToken;

    /** RBAC uloga prijavljenog korisnika. */
    private Role role;

    /** Skup fine-grained permisija koje korisnik ima na osnovu svoje uloge. */
    private Set<Permission> permissions;
}
