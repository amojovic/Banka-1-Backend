package com.banka1.userService.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev prijave korisnika na sistem.
 * Sadrzi email adresu i lozinku u cistom tekstu.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequestDto {

    /** Email adresa korisnika koji se prijavljuje. */
    @NotBlank(message = "Email ne sme da bude prazan")
    @Email(message = "Nije validan email")
    private String email;

    /** Lozinka u cistom tekstu koja se poredi sa hash-om u bazi. */
    @NotBlank(message = "Sifra ne sme da bude prazna")
    private String password;
}
