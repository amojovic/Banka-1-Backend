package com.banka1.verificationService.dto.event;

import com.banka1.verificationService.model.enums.OperationType;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO događaja za objavljivanje generisanja verifikacionog koda ka servisu notifikacija.
 * Sadrži sirovi (neheširani) kod za isporuku klijentu.
 */
@Getter
@Setter
public class VerificationGeneratedEvent {
    /** ID klijenta koji treba da primi verifikacioni kod. */
    private Long clientId;

    /** Sirovi 6-cifreni verifikacioni kod (nije heširan) za slanje putem SMS-a/aplikacije. */
    private String code; // raw

    /** Tip operacije koja zahteva verifikaciju. */
    private OperationType operationType;
}
