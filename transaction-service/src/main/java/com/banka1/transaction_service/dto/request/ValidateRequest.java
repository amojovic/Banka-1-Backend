package com.banka1.transaction_service.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO za zahtev validacije verifikacionog koda.
 * Sadrži ID sesije i kod koji je dao klijent.
 */
@Getter
@Setter
@AllArgsConstructor
public class ValidateRequest {
    /** ID sesije verifikacije za validaciju. */

    private Long sessionId;

    /** Verifikacioni kod koji je uneo klijent. */
    private String code;
}
