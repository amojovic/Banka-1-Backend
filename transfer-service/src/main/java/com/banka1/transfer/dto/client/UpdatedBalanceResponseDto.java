package com.banka1.transfer.dto.client;

import java.math.BigDecimal;

/**
 * Odgovor nakon uspešno izvršenog transfera sa novim stanjima na računima.
 */
public record UpdatedBalanceResponseDto(
        BigDecimal senderBalance,   // Novo stanje pošiljaoca
        BigDecimal receiverBalance // Novo stanje primaoca
) {}
