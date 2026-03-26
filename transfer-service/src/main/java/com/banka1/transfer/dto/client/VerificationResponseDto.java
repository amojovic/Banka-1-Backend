package com.banka1.transfer.dto.client;

/**
 * Rezultat provere verifikacionog koda.
 */
public record VerificationResponseDto(
        boolean valid,             // Da li je kod ispravan
        String status,             // Tekstualni status (npr. VERIFIED)
        int remainingAttempts      // Preostali broj pokušaja unosa koda
) {}
