package com.banka1.clientService.domain;

import com.banka1.clientService.domain.enums.Pol;
import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

/**
 * JPA entitet koji predstavlja klijenta banke.
 * Implementira soft delete – fizicko brisanje je zamenjeno postavljanjem zastavice {@code deleted = true},
 * a {@code @SQLRestriction} automatski iskljucuje obrisane redove iz svih upita.
 */
@Entity
@Table(
        name = "clients",
        indexes = {
                @Index(name = "idx_clients_ime_prezime", columnList = "ime, prezime"),
                @Index(name = "idx_clients_email", columnList = "email")
        }
)
@SQLDelete(sql = "UPDATE clients SET deleted = true WHERE id = ? AND version = ?")
@SQLRestriction("deleted = false")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class Klijent extends BaseEntity {

    /**
     * Ime klijenta.
     */
    @NotBlank
    @Column(nullable = false)
    private String ime;

    /**
     * Prezime klijenta.
     */
    @NotBlank
    @Column(nullable = false)
    private String prezime;

    /**
     * Datum rodjenja klijenta, cuvano kao Unix timestamp (milisekunde).
     */
    @NotNull
    @Column(name = "datum_rodjenja", nullable = false)
    private Long datumRodjenja;

    /**
     * Pol klijenta (M ili Z).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Pol pol;

    /**
     * Email adresa klijenta – mora biti jedinstvena u sistemu.
     */
    @NotBlank
    @Email
    @Column(nullable = false, unique = true)
    private String email;

    /**
     * Broj telefona klijenta.
     */
    private String brojTelefona;

    /**
     * Adresa stanovanja klijenta.
     */
    private String adresa;

    /**
     * Hesh lozinke klijenta (Argon2 ili slicno).
     */
    private String password;

    /**
     * Salt koji se koristi pri hesiranju lozinke.
     */
    @Column(name = "salt_password")
    private String saltPassword;

    /**
     * Jedinstveni maticni broj gradjana klijenta – ne moze se menjati nakon kreiranja.
     */
    @NotBlank
    @Column(nullable = false, unique = true, length = 13)
    private String jmbg;

    @Override
    public String toString() {
        return "Klijent{" +
                "ime='" + ime + '\'' +
                ", prezime='" + prezime + '\'' +
                ", datumRodjenja=" + datumRodjenja +
                ", pol=" + pol +
                ", email='" + email + '\'' +
                ", brojTelefona='" + brojTelefona + '\'' +
                ", adresa='" + adresa + '\'' +
                ", jmbg='[PROTECTED]'" +
                '}';
    }
}
