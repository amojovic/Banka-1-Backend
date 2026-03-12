package com.banka1.userService.repository;

import com.banka1.userService.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repozitorijum za entitet {@link RefreshToken}.
 * Pruza pristup tokenima za obnavljanje JWT sesije.
 */
public interface TokenRepository extends JpaRepository<RefreshToken, Long> {

    /**
     * Pronalazi refresh token po hesiranoj vrednosti.
     *
     * @param refreshToken SHA-256 hash vrednosti tokena koji se trazi
     * @return opcioni refresh token ako postoji
     */
    Optional<RefreshToken> findByValue(String refreshToken);
}
