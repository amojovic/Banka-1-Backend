package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.AccountConcrete;
import com.banka1.account_service.domain.enums.CurrencyCode;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * JPA entitet koji predstavlja tekuci bancarski racun denominovan iskljucivo u RSD.
 * Nasledjuje {@link Account} i dodaje specificna polja: vrstu tekuceg racuna
 * i mesecnu naknadu za odrzavanje.
 */
@NoArgsConstructor
@Getter
@Setter
@Entity
@DiscriminatorValue("CHECKING")
public class CheckingAccount extends Account {

    /** Konkretan podtip tekuceg racuna (licni, stedni, poslovni, itd.). */
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AccountConcrete accountConcrete;

    /** Mesecna naknada za odrzavanje racuna u RSD. Vrednost 0 znaci bez naknade. */
    private BigDecimal odrzavanjeRacuna = BigDecimal.ZERO;

    /**
     * Kreira tekuci racun zadatog podtipa.
     *
     * @param accountConcrete vrsta tekuceg racuna
     */
    public CheckingAccount(AccountConcrete accountConcrete) {
        this.accountConcrete = accountConcrete;
    }

    /**
     * JPA hook koji se poziva pre upisivanja i azuriranja entiteta.
     * Proverava da li je valuta RSD i da li je tip vlasnistva korektan.
     *
     * @throws IllegalStateException ako valuta nije RSD ili podaci nisu konzistentni
     */
    @PrePersist
    @PreUpdate
    private void validate() {
        if (accountConcrete == null) {
            throw new IllegalStateException("accountConcrete je not null (ovde je teoretski moguce doci) ");
        }
        validacija(accountConcrete.getAccountOwnershipType());
        if (getCurrency() == null || getCurrency().getOznaka() != CurrencyCode.RSD) {
            throw new IllegalStateException("Mora RSD");
        }
    }

    /**
     * Postavlja valutu racuna. Baca izuzetak ako valuta nije RSD.
     *
     * @param currency valuta koja se postavlja
     * @throws IllegalArgumentException ako valuta nije RSD
     */
    @Override
    public void setCurrency(Currency currency) {
        if (currency.getOznaka() != CurrencyCode.RSD)
            throw new IllegalArgumentException("Mora RSD");
        super.setCurrency(currency);
    }
}
