package com.banka1.userService.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * JPA entitet koji predstavlja token za potvrdu aktivacije naloga ili reset lozinke.
 * Token se cuva u hesiranom obliku (SHA-256) i moze imati vremenski rok vazenja.
 */
@Entity
@Table(name = "confirmation_token")
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
public class ConfirmationToken extends BaseEntity {

    /** SHA-256 hash vrednosti tokena koji je poslat korisniku. */
    @NotBlank
    @Column(nullable = false, unique = true)
    private String value;

    /** Datum i vreme isteka tokena; {@code null} znaci da token nema vremensko ogranicenje. */
    private LocalDateTime expirationDateTime;

    /** Zaposleni kome token pripada – veza je jedinstvena (1:1). */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zaposlen_id", nullable = false, unique = true)
    private Zaposlen zaposlen;

    /**
     * Kreira potvrdu za datog zaposlenog bez eksplicitnog isteka.
     *
     * @param value hesirana vrednost tokena
     * @param zaposlen zaposleni kome token pripada
     */
    public ConfirmationToken(String value, Zaposlen zaposlen) {
        this.value = value;
        this.zaposlen = zaposlen;
    }
}
