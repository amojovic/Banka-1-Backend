package com.banka1.account_service.domain.enums;

/**
 * Enumeracija koja predstavlja status bankarskog racuna.
 * <p>
 * Statusom se kontrolise da li je racun dostupan za koriscenje ili je deaktiviran.
 */
public enum Status {
    /** Racun je aktivan i dostupan za koriscenje. */
    ACTIVE,

    /** Racun je deaktiviran i nije dostupan za nove transakcije. */
    INACTIVE
}
