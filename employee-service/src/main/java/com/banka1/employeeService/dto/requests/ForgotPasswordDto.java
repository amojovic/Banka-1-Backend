package com.banka1.employeeService.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev za reset zaboravljene lozinke.
 * Sadrzi email adresu korisnika koji je zaboravio lozinku.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ForgotPasswordDto {

    /** Email adresa korisnika cija se lozinka resetuje. */
    @NotBlank(message = "Ne moze prazan string")
    @Email(message = "Nije dobar format email-a")
    private String email;
}
