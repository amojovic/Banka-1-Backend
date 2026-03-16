package com.banka1.clientService.dto.requests;

import com.banka1.clientService.domain.enums.Pol;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO koji zaposleni koristi za azuriranje podataka klijenta.
 * Sva polja su opciona – klijent moze slati samo polja koja zeli da promeni.
 * Password i JMBG se ne mogu menjati.
 */
@Getter
@Setter
public class ClientUpdateRequestDto {

    /** Novo ime klijenta (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String ime;

    /** Novo prezime klijenta (mora imati bar jedan karakter). */
    @Size(min = 1)
    private String prezime;

    /** Novi datum rodjenja klijenta kao Unix timestamp. */
    private Long datumRodjenja;

    /** Novi pol klijenta. */
    private Pol pol;

    /**
     * Nova email adresa klijenta.
     * Proverava se jedinstvenost u bazi pre izmene.
     */
    @Email(message = "Nevalidan format email-a")
    private String email;

    /** Novi broj telefona klijenta u internacionalnom formatu. */
    @Pattern(regexp = "^\\+?[0-9]{8,15}$", message = "Neispravan broj telefona")
    private String brojTelefona;

    /** Nova adresa stanovanja klijenta. */
    private String adresa;
}
