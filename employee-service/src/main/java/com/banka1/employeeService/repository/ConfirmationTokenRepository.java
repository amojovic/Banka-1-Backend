package com.banka1.employeeService.repository;

import com.banka1.employeeService.domain.ConfirmationToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Spring Data JPA repozitorijum za entitet {@link ConfirmationToken}.
 * Pruza metode za pronalazak i brisanje tokena za aktivaciju naloga i reset lozinke.
 */
@Repository
public interface ConfirmationTokenRepository extends JpaRepository<ConfirmationToken, Long> {

    /**
     * Pronalazi token po hesiranoj vrednosti.
     *
     * @param value SHA-256 hash vrednost tokena
     * @return opcioni token ako postoji
     */
    Optional<ConfirmationToken> findByValue(String value);

    /**
     * Brise sve tokene koji imaju postavljeno vreme isteka i to vreme je proslo.
     * Koristi se za periodicno ciscenje isteklih tokena.
     *
     * @param now trenutno vreme – svi tokeni ciji je {@code expirationDateTime} pre ovog trenutka ce biti obrisani
     */
    void deleteAllByExpirationDateTimeNotNullAndExpirationDateTimeBefore(LocalDateTime now);
}
