package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Imutabilni JPA entitet koji predstavlja valutu podrzanu od strane banke.
 * Podaci o valutama se ucitavaju putem Liquibase seed migracija i ne menjaju se u runtime-u.
 */
@Entity
@org.hibernate.annotations.Immutable
@Table(
        name = "currency_table"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Currency extends BaseEntity {

    /** Pun naziv valute (npr. "Srpski dinar"). */
    @NotBlank
    @Column(nullable = false, updatable = false)
    private String naziv;

    /** ISO kod valute (npr. RSD, EUR, USD). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, unique = true)
    private CurrencyCode oznaka;

    /** Simbol valute (npr. "din", "€", "$"). */
    @NotBlank
    @Column(nullable = false, updatable = false, unique = true)
    private String simbol;

    /** Skup zemalja u kojima se ova valuta koristi. */
    @ElementCollection
    @CollectionTable(name = "currency_countries", joinColumns = @JoinColumn(name = "currency_id"))
    @Column(name = "country", nullable = false)
    private Set<String> countries = new HashSet<>();

    /** Kratak opis valute. */
    @NotBlank
    @Column(nullable = false, updatable = false)
    private String opis;

    /** Status valute — neaktivne valute ne mogu se koristiti za kreiranje novih racuna. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Status status;

    /**
     * Kreira novu valutu sa svim obaveznim podacima.
     *
     * @param naziv     pun naziv valute
     * @param oznaka    ISO kod valute
     * @param simbol    simbol valute
     * @param countries skup zemalja u kojima se valuta koristi
     * @param opis      kratak opis valute
     * @param status    inicijalni status valute
     */
    public Currency(String naziv, CurrencyCode oznaka, String simbol, Set<String> countries, String opis, Status status) {
        this.naziv = naziv;
        this.oznaka = oznaka;
        this.simbol = simbol;
        this.countries = countries;
        this.opis = opis;
        this.status = status;
    }

    /**
     * Vraca nepromenjiv pogled na skup zemalja ove valute.
     *
     * @return nepromenljivi set zemalja
     */
    public Set<String> getCountries() {
        return Collections.unmodifiableSet(countries);
    }
}
