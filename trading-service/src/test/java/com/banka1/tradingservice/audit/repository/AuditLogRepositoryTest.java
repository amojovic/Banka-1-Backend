package com.banka1.tradingservice.audit.repository;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-2: Spring Data slice test za {@link AuditLogRepository}.
 *
 * <p>H2 u PostgreSQL kompat. modu (vidi {@code application-test.properties}),
 * Liquibase iskljucen — Hibernate gradi semu iz {@code AuditLog} mapiranja.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AuditLogRepositoryTest {

    @Autowired
    private AuditLogRepository repository;

    private AuditLog row(AuditActionType type, Long actorId, LocalDateTime createdAt) {
        return AuditLog.builder()
                .actorId(actorId)
                .actorName(actorId == null ? "SYSTEM" : "Actor-" + actorId)
                .actionType(type)
                .targetType("ORDER")
                .targetId("42")
                .details("old=PENDING, new=APPROVED")
                .createdAt(createdAt)
                .build();
    }

    @Test
    void persistsAndAssignsId() {
        AuditLog saved = repository.saveAndFlush(
                row(AuditActionType.ORDER_APPROVED, 7L, LocalDateTime.of(2026, 5, 18, 10, 0)));

        assertNotNull(saved.getId(), "BIGSERIAL PK mora biti dodeljen");
        assertEquals(AuditActionType.ORDER_APPROVED, repository.findById(saved.getId()).orElseThrow().getActionType());
    }

    @Test
    void prePersistFillsCreatedAtWhenNull() {
        AuditLog entity = row(AuditActionType.TAX_RUN_MANUAL, 1L, null);

        AuditLog saved = repository.saveAndFlush(entity);

        assertNotNull(saved.getCreatedAt(), "@PrePersist mora popuniti createdAt kad je null");
    }

    @Test
    void allowsNullActorIdForSystemActor() {
        AuditLog saved = repository.saveAndFlush(
                row(AuditActionType.TAX_RUN_SCHEDULED, null, LocalDateTime.of(2026, 5, 18, 0, 5)));

        AuditLog reloaded = repository.findById(saved.getId()).orElseThrow();
        assertNull(reloaded.getActorId(), "actorId null = SYSTEM aktor");
        assertEquals("SYSTEM", reloaded.getActorName());
    }

    @Test
    void specificationFiltersByActionTypeActorAndRange() {
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 7L, LocalDateTime.of(2026, 5, 10, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_DECLINED, 7L, LocalDateTime.of(2026, 5, 15, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 8L, LocalDateTime.of(2026, 5, 15, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 7L, LocalDateTime.of(2026, 5, 20, 9, 0)));

        Specification<AuditLog> spec = (root, q, cb) -> cb.and(
                cb.equal(root.get("actionType"), AuditActionType.ORDER_APPROVED),
                cb.equal(root.get("actorId"), 7L),
                cb.greaterThanOrEqualTo(root.get("createdAt"), LocalDateTime.of(2026, 5, 12, 0, 0)),
                cb.lessThanOrEqualTo(root.get("createdAt"), LocalDateTime.of(2026, 5, 25, 0, 0)));

        Page<AuditLog> result = repository.findAll(spec,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(1, result.getTotalElements(),
                "samo ORDER_APPROVED/actor7 u opsegu 12-25.5 sme proci");
        assertEquals(LocalDateTime.of(2026, 5, 20, 9, 0), result.getContent().get(0).getCreatedAt());
    }

    @Test
    void sortsNewestFirst() {
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 1L, LocalDateTime.of(2026, 5, 1, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 1L, LocalDateTime.of(2026, 5, 3, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 1L, LocalDateTime.of(2026, 5, 2, 9, 0)));

        Page<AuditLog> page = repository.findAll((Specification<AuditLog>) (root, q, cb) -> cb.conjunction(),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertTrue(page.getContent().get(0).getCreatedAt()
                        .isAfter(page.getContent().get(1).getCreatedAt()),
                "newest-first sort na createdAt");
        assertEquals(LocalDateTime.of(2026, 5, 3, 9, 0), page.getContent().get(0).getCreatedAt());
    }
}
