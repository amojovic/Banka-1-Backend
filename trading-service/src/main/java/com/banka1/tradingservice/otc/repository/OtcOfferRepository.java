package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface OtcOfferRepository extends JpaRepository<OtcOffer, Long> {

    /** Aktivne ponude za korisnika (kupca ili prodavca) — Stranica: Aktivne ponude. */
    List<OtcOffer> findByBuyerIdAndStatusInOrSellerIdAndStatusIn(
            Long buyerId, List<OtcOfferStatus> buyerStatuses,
            Long sellerId, List<OtcOfferStatus> sellerStatuses
    );

    /** Bulk lookup za expired sweeper cron. */
    List<OtcOffer> findByStatusInAndSettlementDateBefore(List<OtcOfferStatus> statuses, LocalDate before);
}
