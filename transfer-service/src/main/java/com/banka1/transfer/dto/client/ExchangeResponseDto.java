package com.banka1.transfer.dto.client;

import java.math.BigDecimal;


/**
 * Odgovor menjačnice sa detaljima obračunate konverzije valuta.
 */
public record ExchangeResponseDto(
        String fromCurrency,   // Izvorna valuta
        String toCurrency,     // Ciljna valuta
        BigDecimal fromAmount, // Iznos u izvornoj valuti
        BigDecimal toAmount,   // Iznos u ciljnoj valuti nakon konverzije
        BigDecimal rate,       // Primenjeni kurs
        BigDecimal commission  // Obračunata provizija
) {}

