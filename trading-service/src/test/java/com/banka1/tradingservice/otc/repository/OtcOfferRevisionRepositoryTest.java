package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcOfferRevision;
import com.banka1.tradingservice.otc.domain.OtcRevisionAction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * WP-16 (Celina 4.2): Spring Data slice test za {@link OtcOfferRevisionRepository}.
 *
 * <p>H2 u PostgreSQL kompat. modu (vidi {@code application-test.properties}),
 * Liquibase iskljucen — Hibernate gradi semu iz {@code OtcOfferRevision} mapiranja.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OtcOfferRevisionRepositoryTest {

    @Autowired
    private OtcOfferRevisionRepository repository;

    private OtcOfferRevision revision(Long offerId, OtcRevisionAction action, LocalDateTime createdAt) {
        return OtcOfferRevision.builder()
                .offerId(offerId)
                .action(action)
                .actorUserId(100L)
                .actorName("Marko Markovic")
                .actorRole("BUYER")
                .newAmount(10)
                .newPricePerStock(new BigDecimal("150.00"))
                .newPremium(new BigDecimal("400.00"))
                .newSettlementDate(LocalDate.of(2026, 8, 1))
                .createdAt(createdAt)
                .build();
    }

    @Test
    void persistsAndAssignsId() {
        OtcOfferRevision saved = repository.saveAndFlush(
                revision(1L, OtcRevisionAction.CREATE, LocalDateTime.of(2026, 5, 19, 10, 0)));

        assertNotNull(saved.getId(), "BIGSERIAL PK mora biti dodeljen");
        OtcOfferRevision reloaded = repository.findById(saved.getId()).orElseThrow();
        assertEquals(OtcRevisionAction.CREATE, reloaded.getAction());
        assertEquals("BUYER", reloaded.getActorRole());
    }

    @Test
    void prePersistFillsCreatedAtWhenNull() {
        OtcOfferRevision saved = repository.saveAndFlush(revision(1L, OtcRevisionAction.CREATE, null));

        assertNotNull(saved.getCreatedAt(), "@PrePersist mora popuniti createdAt kad je null");
    }

    @Test
    void allowsNullOldFieldsForCreateAction() {
        OtcOfferRevision entity = revision(1L, OtcRevisionAction.CREATE, LocalDateTime.now());
        entity.setOldAmount(null);
        entity.setOldPricePerStock(null);
        entity.setOldPremium(null);
        entity.setOldSettlementDate(null);

        OtcOfferRevision reloaded = repository.findById(repository.saveAndFlush(entity).getId()).orElseThrow();

        assertNull(reloaded.getOldAmount(), "CREATE: stara polja su null");
        assertNull(reloaded.getOldPricePerStock());
        assertNull(reloaded.getOldPremium());
        assertNull(reloaded.getOldSettlementDate());
    }

    @Test
    void findByOfferIdReturnsTrailOldestFirst() {
        repository.saveAndFlush(revision(7L, OtcRevisionAction.COUNTER, LocalDateTime.of(2026, 5, 12, 9, 0)));
        repository.saveAndFlush(revision(7L, OtcRevisionAction.CREATE, LocalDateTime.of(2026, 5, 10, 9, 0)));
        repository.saveAndFlush(revision(7L, OtcRevisionAction.ACCEPT, LocalDateTime.of(2026, 5, 14, 9, 0)));
        // Druga ponuda — ne sme se pojaviti u tragu ponude 7.
        repository.saveAndFlush(revision(8L, OtcRevisionAction.CREATE, LocalDateTime.of(2026, 5, 11, 9, 0)));

        List<OtcOfferRevision> trail = repository.findByOfferIdOrderByCreatedAtAscIdAsc(7L);

        assertEquals(3, trail.size(), "samo revizije ponude 7");
        assertEquals(OtcRevisionAction.CREATE, trail.get(0).getAction(), "najstariji prvi");
        assertEquals(OtcRevisionAction.COUNTER, trail.get(1).getAction());
        assertEquals(OtcRevisionAction.ACCEPT, trail.get(2).getAction());
    }

    @Test
    void findByOfferIdReturnsEmptyForUnknownOffer() {
        assertEquals(0, repository.findByOfferIdOrderByCreatedAtAscIdAsc(999L).size());
    }
}
