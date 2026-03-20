package com.banka1.employeeService.dto.requests;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev obnavljanja JWT pristupnog tokena.
 * Sadrzi nehesirani refresh token koji se koristi za izdavanje novog para tokena.
 */
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class RefreshTokenRequestDto {

    /** Nehesirani refresh token primljen pri prethodnoj prijavi. */
    @NotBlank(message = "RefreshToken ne sme da bude prazan")
    private String refreshToken;
}
