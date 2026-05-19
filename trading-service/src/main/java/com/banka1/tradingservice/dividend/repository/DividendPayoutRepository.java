package com.banka1.tradingservice.dividend.repository;

import com.banka1.tradingservice.dividend.domain.DividendPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * WP-14 (Celina 3.7): Spring Data repozitorijum za {@link DividendPayout}.
 */
@Repository
public interface DividendPayoutRepository extends JpaRepository<DividendPayout, Long> {

    /**
     * Vraca sve isplate dividende za jednog drzaoca, najnovije prvo. Koristi se
     * za {@code GET /dividends} (istorija dividendi na portfolio strani).
     *
     * @param userId ID drzaoca
     * @return lista isplata, sortirana opadajuce po datumu isplate
     */
    List<DividendPayout> findByUserIdOrderByPaymentDateDesc(Long userId);

    /**
     * Vraca isplate dividende za jednog drzaoca i jednu poziciju (listing),
     * najnovije prvo. Koristi se za per-poziciju istoriju na portfolio strani.
     *
     * @param userId    ID drzaoca
     * @param listingId ID listinga
     * @return lista isplata za tu poziciju, sortirana opadajuce po datumu isplate
     */
    List<DividendPayout> findByUserIdAndListingIdOrderByPaymentDateDesc(Long userId, Long listingId);

    /**
     * Idempotency guard: da li je dividenda za {@code (userId, listingId, paymentDate)}
     * vec isplacena. Spreca duplu isplatu ako se obracun pokrene vise puta isti
     * dan (pored unique constraint-a na tabeli).
     *
     * @param userId      ID drzaoca
     * @param listingId   ID listinga
     * @param paymentDate datum obracuna
     * @return {@code true} ako vec postoji isplata za tu kombinaciju
     */
    boolean existsByUserIdAndListingIdAndPaymentDate(Long userId, Long listingId, LocalDate paymentDate);
}
