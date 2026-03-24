package com.banka1.account_service.domain;

import com.banka1.account_service.domain.enums.AccountOwnershipType;
import com.banka1.account_service.domain.enums.Status;
import jakarta.persistence.*;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "account_table",
        indexes = {
            @Index(name = "idx_account_vlasnik", columnList = "vlasnik"),
            @Index(name = "idx_account_broj", columnList = "broj_racuna"),
            @Index(name = "idx_account_company", columnList = "company_id"),
            @Index(name = "idx_account_ime_vlasnika", columnList = "ime_vlasnika_racuna"),
            @Index(name = "idx_account_prezime_vlasnika", columnList = "prezime_vlasnika_racuna"),
            @Index(name = "idx_account_ime_prezime_vlasnika", columnList = "ime_vlasnika_racuna, prezime_vlasnika_racuna")
        }
)
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "account_type", discriminatorType = DiscriminatorType.STRING)
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
//todo da li staviti datum isteka na null
public abstract class Account extends BaseEntity{
    @NotBlank
    @Column(nullable = false,unique = true,updatable = false)
    private String brojRacuna;
    @NotBlank
    @Column(nullable = false)
    private String imeVlasnikaRacuna;
    @NotBlank
    @Column(nullable = false)
    private String prezimeVlasnikaRacuna;
    @NotBlank
    @Column(nullable = false)
    private String nazivRacuna;
    @Column(nullable = false)
    private Long vlasnik;
    //@DecimalMin(value = "0.00", inclusive = true)
    @Column(nullable = false)
    private BigDecimal stanje= BigDecimal.ZERO;
    @DecimalMin(value = "0.00", inclusive = true)
    @Column(nullable = false)
    private BigDecimal raspolozivoStanje= BigDecimal.ZERO;
    @Column(nullable = false)
    private Long zaposlen;
    //todo mozda LocalDateTime
    @CreationTimestamp
    @Column(name = "datum_i_vreme_kreiranja",nullable = false,updatable = false)
    private LocalDateTime datumIVremeKreiranja;
    private LocalDate datumIsteka;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "currency_id", nullable = false)
    private Currency currency;
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Status status=Status.ACTIVE;
    @DecimalMin(value = "0.00", inclusive = true)
//    @Column(nullable = false)
    private BigDecimal dnevniLimit;
    @DecimalMin(value = "0.00", inclusive = true)
//    @Column(nullable = false)
    private BigDecimal mesecniLimit;
    @DecimalMin(value = "0.00", inclusive = true)
    @Column(nullable = false)
    private BigDecimal dnevnaPotrosnja=BigDecimal.ZERO;
    @DecimalMin(value = "0.00", inclusive = true)
    @Column(nullable = false)
    private BigDecimal mesecnaPotrosnja=BigDecimal.ZERO;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;


    protected void validacija(AccountOwnershipType ownershipType) {
        if (ownershipType == null) {
            throw new IllegalStateException("Ownership type is required");
        }

        if (ownershipType == AccountOwnershipType.BUSINESS && this.getCompany() == null) {
            throw new IllegalStateException("Company is required for BUSINESS account");
        }

        if (ownershipType == AccountOwnershipType.PERSONAL && this.getCompany() != null) {
            throw new IllegalStateException("Company must be null for PERSONAL account");
        }
    }


}
