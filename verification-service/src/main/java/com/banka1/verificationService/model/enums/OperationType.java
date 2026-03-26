package com.banka1.verificationService.model.enums;

/**
 * Enumeracija tipova operacija koje zahtijevaju verifikaciju.
 * Koristi se za kategorizaciju tipa transakcije ili akcije koja se verifikuje.
 */
public enum OperationType {
    /** Transakcija plaćanja koja zahteva verifikaciju. */
    PAYMENT,
    /** Transfer novca između računa. */
    TRANSFER,
    /** Promena limita potrošnje na računu. */
    LIMIT_CHANGE,
    /** Zahtev za novu kreditnu/debitnu karticu. */
    CARD_REQUEST,
    /** Prijava za kredit. */
    LOAN_REQUEST
}
