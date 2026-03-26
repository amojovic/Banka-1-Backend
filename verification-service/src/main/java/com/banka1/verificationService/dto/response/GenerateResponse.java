package com.banka1.verificationService.dto.response;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO za odgovor nakon generisanja sesije verifikacije.
 * Sadrži ID sesije koji se može koristiti za naredne operacije.
 */
@Getter
@Setter
public class GenerateResponse {
    /** ID novo-kreirane sesije verifikacije. */
    private Long sessionId;

    /**
     * Konstruktor za kreiranje odgovora sa ID-om sesije.
     *
     * @param sessionId ID generisane sesije
     */
    public GenerateResponse(Long sessionId) {
        this.sessionId = sessionId;
    }
}
