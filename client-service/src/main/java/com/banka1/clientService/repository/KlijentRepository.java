package com.banka1.clientService.repository;

import com.banka1.clientService.domain.Klijent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data JPA repozitorijum za entitet {@link Klijent}.
 * Svi upiti automatski iskljucuju soft-obrisane zapise zahvaljujuci
 * {@code @SQLRestriction("deleted = false")} na entitetu.
 */
@Repository
public interface KlijentRepository extends JpaRepository<Klijent, Long> {

    /**
     * Pronalazi klijenta po email adresi.
     *
     * @param email email adresa klijenta
     * @return opcioni klijent ako postoji
     */
    Optional<Klijent> findByEmail(String email);

    /**
     * Proverava da li klijent sa zadatom email adresom vec postoji.
     *
     * @param email email adresa za proveru
     * @return {@code true} ako adresa vec postoji
     */
    boolean existsByEmail(String email);

    /**
     * Pronalazi klijenta po JMBG-u.
     *
     * @param jmbg JMBG klijenta
     * @return opcioni klijent ako postoji
     */
    Optional<Klijent> findByJmbg(String jmbg);

    /**
     * Proverava da li klijent sa zadatim JMBG-om vec postoji.
     *
     * @param jmbg JMBG za proveru
     * @return {@code true} ako JMBG vec postoji
     */
    boolean existsByJmbg(String jmbg);

    /**
     * Pretrazuje klijente po kombinaciji filtera sa paginacijom.
     * Svaki filter koristi case-insensitive LIKE pretragu; prazan string se ponasa kao wildcard.
     *
     * @param ime filter po imenu
     * @param prezime filter po prezimenu
     * @param email filter po email adresi
     * @param pageable parametri paginacije i sortiranja
     * @return stranica klijenata koji zadovoljavaju sve filtere
     */
    @Query("SELECT k FROM Klijent k WHERE " +
            "LOWER(k.ime) LIKE LOWER(CONCAT('%', :ime, '%')) AND " +
            "LOWER(k.prezime) LIKE LOWER(CONCAT('%', :prezime, '%')) AND " +
            "LOWER(k.email) LIKE LOWER(CONCAT('%', :email, '%')) AND " +
            "k.deleted = false ORDER BY k.prezime ASC")
    Page<Klijent> searchClients(
            @Param("ime") String ime,
            @Param("prezime") String prezime,
            @Param("email") String email,
            Pageable pageable
    );

    /**
     * Pretrazuje klijente jednim tekstualnim upitom po imenu, prezimenu i emailu.
     *
     * @param query tekstualni upit za pretragu
     * @param pageable parametri paginacije i sortiranja
     * @return stranica klijenata koji odgovaraju upitu
     */
    @Query("SELECT k FROM Klijent k WHERE " +
            "k.deleted = false AND (" +
            "LOWER(k.ime) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(k.prezime) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
            "LOWER(k.email) LIKE LOWER(CONCAT('%', :query, '%'))) " +
            "ORDER BY k.prezime ASC")
    Page<Klijent> globalSearchClients(@Param("query") String query, Pageable pageable);
}
