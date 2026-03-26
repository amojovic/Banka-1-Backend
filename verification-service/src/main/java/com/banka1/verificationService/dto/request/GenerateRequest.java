package com.banka1.verificationService.dto.request;

import com.banka1.verificationService.model.enums.OperationType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO za zahtev generisanja nove sesije verifikacije.
 * Sadrži neophodne informacije za kreiranje i slanje verifikacionog koda.
 */
@Getter
@Setter
public class GenerateRequest {
    /** ID klijenta koji zahteva verifikaciju. */
    @NotNull(message = "clientId is required.")
    private Long clientId;

    /** Tip operacije koja zahteva verifikaciju. */
    @NotNull(message = "operationType is required.")
    private OperationType operationType;

    /** Obavezni ID povezanog entiteta (npr., ID transakcije ili zahteva). */
    @NotBlank(message = "relatedEntityId is required.")
    private String relatedEntityId;

    /** Email adresa klijenta na koju se šalje verifikacioni kod. */
    @NotBlank(message = "clientEmail is required.")
    @Email(message = "clientEmail must be a valid email address.")
    private String clientEmail;
}
