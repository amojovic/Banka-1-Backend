package com.banka1.transfer.dto.client;

/**
 * Zahtev koji se šalje Verification servisu za validaciju 2FA koda.
 */
public record VerificationValidateRequestDto(
        Long sessionId,   // ID sesije
        String code       // Kod koji je korisnik uneo
) {}
