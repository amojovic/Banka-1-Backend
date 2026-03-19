package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import jakarta.persistence.*;
import lombok.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

@Entity
@org.hibernate.annotations.Immutable
@Table(
        name = "currency_table"
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Currency extends BaseEntity{
    @Column(nullable = false,updatable = false)
    private String naziv;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false,updatable = false,unique = true)
    private CurrencyCode oznaka;
    //ne mora nullable=false
    @Column(nullable = false,updatable = false,unique = true)
    private char simbol;
    @ElementCollection
    @CollectionTable(name = "currency_countries", joinColumns = @JoinColumn(name = "currency_id"))
    @Column(name = "country", nullable = false)
    private Set<String> countries = new HashSet<>();
    @Column(nullable = false,updatable = false)
    private String opis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false,updatable = false)
    private Status status;

    public Currency(String naziv, CurrencyCode oznaka, char simbol, Set<String> countries, String opis, Status status) {
        this.naziv = naziv;
        this.oznaka = oznaka;
        this.simbol = simbol;
        this.countries = countries;
        this.opis = opis;
        this.status = status;
    }

    public Set<String> getCountries() {
        return Collections.unmodifiableSet(countries);
    }

}
