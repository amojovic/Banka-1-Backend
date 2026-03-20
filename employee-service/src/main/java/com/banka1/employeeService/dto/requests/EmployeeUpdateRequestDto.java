package com.banka1.employeeService.dto.requests;

import com.banka1.employeeService.domain.enums.Role;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji administrator koristi za azuriranje podataka zaposlenog.
 * Sva polja su opciona – klijent moze slati samo polja koja zeli da promeni.
 */
@Getter
@Setter
public class EmployeeUpdateRequestDto {

    /** Novo ime zaposlenog (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String ime;

    /** Novo prezime zaposlenog (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String prezime;

    /** Novi broj telefona zaposlenog u internacionalnom formatu. */
    @Pattern(regexp = "^\\+?[0-9]{8,15}$")
    private String brojTelefona;

    /** Nova adresa stanovanja zaposlenog. */
    private String adresa;

    /** Nova pozicija zaposlenog (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String pozicija;

    /** Novi departman zaposlenog (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String departman;

    /** Novi status aktivnosti naloga; {@code false} deaktivira zaposlenog. */
    private Boolean aktivan;

    /** Nova uloga zaposlenog; promena uloge azurira i skup permisija. */
    private Role role;
}
