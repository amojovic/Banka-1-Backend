package com.banka1.userService.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev ponovnog slanja aktivacionog mejla.
 * Koristi se kada korisnik nije primio ili je izgubio originalni aktivacioni link.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ResendActivationDto {

    /** Email adresa neaktiviranog korisnika kome se ponovo salje aktivacioni link. */
    @Email
    @NotBlank
    private String email;
}
