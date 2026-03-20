package com.banka1.employeeService.dto.requests;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DTO za zahtev odjave korisnika.
 * Sadrzi nehesirani refresh token koji treba invalidirati.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LogoutRequestDto {

    /** Nehesirani refresh token koji se brise pri odjavi. */
    @NotBlank
    private String refreshToken;
}
