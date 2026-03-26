package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.AccountOwnershipType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA entitet koji predstavlja devizni (FX) bankarski racun.
 * Nasledjuje {@link Account} i nije dozvoljen u RSD valuti.
 * Ima tip vlasnistva (licni ili poslovni).
 */
@NoArgsConstructor
@Getter
@Setter
@Entity
@DiscriminatorValue("FX")
@AllArgsConstructor
public class FxAccount extends Account {

    /** Tip vlasnistva deviznog racuna (PERSONAL ili BUSINESS). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountOwnershipType accountOwnershipType;

    /**
     * JPA hook koji se poziva pre upisivanja i azuriranja entiteta.
     * Proverava da je tip vlasnistva postavljen i da valuta nije RSD.
     *
     * @throws IllegalStateException ako valuta jeste RSD ili podaci nisu konzistentni
     */
    @PrePersist
    @PreUpdate
    private void validate() {
        if (accountOwnershipType == null) {
            throw new IllegalStateException("accountConcrete je not null (ovde je teoretski moguce doci) ");
        }
        validacija(accountOwnershipType);
        if (getCurrency() == null || getCurrency().getOznaka() == CurrencyCode.RSD) {
            throw new IllegalStateException("Ne moze RSD");
        }
    }

    /**
     * Postavlja valutu racuna. Baca izuzetak ako je valuta RSD.
     *
     * @param currency valuta koja se postavlja
     * @throws IllegalArgumentException ako je valuta RSD
     */
    public void setCurrency(Currency currency) {
        if (currency.getOznaka() == CurrencyCode.RSD)
            throw new IllegalArgumentException("Ne moze RSD");
        super.setCurrency(currency);
    }
}
