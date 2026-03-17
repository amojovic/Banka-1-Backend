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
     * Pronalazi klijenta po JMBG-u.
     *
     * @param jmbg JMBG klijenta
     * @return opcioni klijent ako postoji
     */
    Optional<Klijent> findByJmbg(String jmbg);

    /**
     * Proverava da li postoji aktivan klijent sa zadatim emailom koji nije klijent sa datim ID-em.
     * Koristi se pre azuriranja radi rane detekcije duplikata emaila.
     *
     * @param email email adresa koju treba proveriti
     * @param id    ID klijenta koji se azurira (iskljucuje se iz provere)
     * @return true ako postoji drugi klijent sa istim emailom
     */
    boolean existsByEmailAndIdNot(String email, Long id);

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
