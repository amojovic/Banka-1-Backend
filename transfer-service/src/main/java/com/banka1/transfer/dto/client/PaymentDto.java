package com.banka1.transfer.dto.client;

import java.math.BigDecimal;

/**
 * Podaci koji se šalju Account servisu za izvršenje atomskog prenosa sredstava.
 */
public record PaymentDto(
        String fromAccountNumber, // Račun pošiljaoca
        String toAccountNumber,   // Račun primaoca
        BigDecimal fromAmount,    // Iznos koji se skida (u valuti pošiljaoca)
        BigDecimal toAmount,      // Iznos koji se leže (u valuti primaoca)
        BigDecimal commission,     // Provizija
        Long clientId             // ID klijenta radi validacije vlasništva
) {}