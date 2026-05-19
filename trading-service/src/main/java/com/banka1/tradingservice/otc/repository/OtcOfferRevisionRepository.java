package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcOfferRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * WP-16 (Celina 4.2): Spring Data repozitorijum za {@link OtcOfferRevision}.
 *
 * <p>Cita kompletnu istoriju jednog OTC pregovora — revizioni trag
 * sortiran hronoloski (najstariji prvi) za {@code GET /otc/offers/{id}/history}.
 */
@Repository
public interface OtcOfferRevisionRepository extends JpaRepository<OtcOfferRevision, Long> {

    /**
     * Kompletan revizioni trag jedne ponude, najstariji red prvi.
     *
     * @param offerId ID {@code OtcOffer} ponude
     * @return revizije sortirane uzlazno po {@code createdAt} pa po {@code id}
     */
    List<OtcOfferRevision> findByOfferIdOrderByCreatedAtAscIdAsc(Long offerId);
}
