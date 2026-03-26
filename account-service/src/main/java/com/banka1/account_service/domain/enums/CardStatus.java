package com.banka1.account_service.domain.enums;

/**
 * Enumeracija koja definise moguce statuse bankarske kartice.
 */
public enum CardStatus {

    /** Kartica je aktivna i moze se koristiti za transakcije. */
    ACTIVATED,

    /** Kartica je deaktivirana i ne moze se koristiti. */
    DEACTIVATED,

    /** Kartica je blokirana (npr. zbog sumnje na zloupotrebu). */
    BLOCKED
}
