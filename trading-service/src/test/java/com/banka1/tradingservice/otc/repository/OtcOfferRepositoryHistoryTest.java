package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcOffer;
import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-16 (Celina 4.2): slice test koji vrti {@code JpaSpecificationExecutor} nad
 * {@link OtcOfferRepository}-jem na realnoj H2 bazi — verifikuje da filtriran
 * upit istorije pregovora (ucesnik + status + vremenski opseg) radi protiv
 * {@code otc_offers} seme. Logika gradnje {@link Specification} ovde zrcali
 * {@code OtcService.historyForUser}.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OtcOfferRepositoryHistoryTest {

    private static final long CALLER = 100L;

    @Autowired
    private OtcOfferRepository repository;

    private OtcOffer offer(Long buyerId, Long sellerId, OtcOfferStatus status, LocalDateTime lastModified) {
        OtcOffer o = new OtcOffer();
        o.setStockTicker("AAPL");
        o.setBuyerId(buyerId);
        o.setSellerId(sellerId);
        o.setAmount(10);
        o.setPricePerStock(new BigDecimal("150.00"));
        o.setPremium(new BigDecimal("400.00"));
        o.setSettlementDate(LocalDate.of(2026, 8, 1));
        o.setStatus(status);
        o.setCreatedAt(lastModified);
        o.setLastModified(lastModified);
        return o;
    }

    /** Zrcali OtcService.historyForUser spec gradnju (bez counterparty-po-imenu dela). */
    private Specification<OtcOffer> historySpec(Long userId, OtcOfferStatus status,
                                                LocalDateTime from, LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.or(
                    cb.equal(root.get("buyerId"), userId),
                    cb.equal(root.get("sellerId"), userId)));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("lastModified"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("lastModified"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    @BeforeEach
    void seed() {
        // Caller je kupac.
        repository.saveAndFlush(offer(CALLER, 200L, OtcOfferStatus.ACCEPTED, LocalDateTime.of(2026, 5, 10, 9, 0)));
        // Caller je prodavac.
        repository.saveAndFlush(offer(300L, CALLER, OtcOfferStatus.REJECTED, LocalDateTime.of(2026, 5, 15, 9, 0)));
        // Caller je kupac, ali jos aktivna (PENDING_SELLER) — istorija je preko SVIH statusa.
        repository.saveAndFlush(offer(CALLER, 400L, OtcOfferStatus.PENDING_SELLER, LocalDateTime.of(2026, 5, 18, 9, 0)));
        // Caller uopste nije ucesnik — ne sme se pojaviti.
        repository.saveAndFlush(offer(500L, 600L, OtcOfferStatus.WITHDRAWN, LocalDateTime.of(2026, 5, 16, 9, 0)));
    }

    @Test
    void returnsAllOffersWhereCallerParticipatesAcrossAllStatuses() {
        List<OtcOffer> result = repository.findAll(historySpec(CALLER, null, null, null));

        assertEquals(3, result.size(), "3 ponude sa caller-om kao kupcem ili prodavcem");
        assertTrue(result.stream().allMatch(o ->
                o.getBuyerId().equals(CALLER) || o.getSellerId().equals(CALLER)));
    }

    @Test
    void filtersByStatus() {
        List<OtcOffer> result = repository.findAll(historySpec(CALLER, OtcOfferStatus.REJECTED, null, null));

        assertEquals(1, result.size());
        assertEquals(OtcOfferStatus.REJECTED, result.get(0).getStatus());
    }

    @Test
    void filtersByDateRange() {
        List<OtcOffer> result = repository.findAll(historySpec(CALLER, null,
                LocalDateTime.of(2026, 5, 12, 0, 0),
                LocalDateTime.of(2026, 5, 16, 0, 0)));

        assertEquals(1, result.size(), "samo 15.5 ponuda u opsegu 12-16.5 sa caller-om");
        assertEquals(OtcOfferStatus.REJECTED, result.get(0).getStatus());
    }

    @Test
    void excludesOffersWhereCallerIsNotParticipant() {
        List<OtcOffer> result = repository.findAll(historySpec(CALLER, null, null, null));

        assertTrue(result.stream().noneMatch(o -> o.getStatus() == OtcOfferStatus.WITHDRAWN),
                "ponuda 500/600 (caller nije ucesnik) ne sme proci");
    }
}
