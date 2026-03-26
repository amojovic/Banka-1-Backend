package com.banka1.verificationService.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO za zahtev validacije verifikacionog koda.
 * Sadrži ID sesije i kod koji je dao klijent.
 */
@Getter
@Setter
public class ValidateRequest {
    /** ID sesije verifikacije za validaciju. */
    @NotNull(message = "sessionId is required.")
    private Long sessionId;

    /** Verifikacioni kod koji je uneo klijent. */
    @NotBlank(message = "code is required.")
    @Pattern(regexp = "^\\d{6}$", message = "code must be a 6-digit number.")
    private String code;
}
