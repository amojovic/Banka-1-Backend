package com.banka1.tradingservice.audit.service;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import com.banka1.tradingservice.audit.dto.AuditLogDto;
import com.banka1.tradingservice.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-2: integracioni test za {@link AuditQueryService} nad realnom H2 bazom.
 *
 * <p>Za razliku od cisto unit varijante ({@code AuditQueryServiceTest}), ovde
 * se {@link org.springframework.data.jpa.domain.Specification} zaista izvrsava
 * kroz Hibernate, pa pokriva i telo {@code buildSpecification} lambdi.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(AuditQueryService.class)
class AuditQueryServiceDataJpaTest {

    @Autowired
    private AuditQueryService service;

    @Autowired
    private AuditLogRepository repository;

    private AuditLog row(AuditActionType type, Long actorId, LocalDateTime createdAt) {
        return AuditLog.builder()
                .actorId(actorId)
                .actorName(actorId == null ? "SYSTEM" : "Actor-" + actorId)
                .actionType(type)
                .targetType("ORDER")
                .targetId("1")
                .details("d")
                .createdAt(createdAt)
                .build();
    }

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 7L, LocalDateTime.of(2026, 5, 10, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_DECLINED, 7L, LocalDateTime.of(2026, 5, 15, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 8L, LocalDateTime.of(2026, 5, 15, 9, 0)));
        repository.saveAndFlush(row(AuditActionType.ORDER_APPROVED, 7L, LocalDateTime.of(2026, 5, 20, 9, 0)));
    }

    @Test
    void noFiltersReturnsAllNewestFirst() {
        Page<AuditLogDto> page = service.search(null, null, null, null,
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(4, page.getTotalElements());
        assertTrue(page.getContent().get(0).createdAt()
                .isAfter(page.getContent().get(1).createdAt()), "newest-first");
    }

    @Test
    void filtersByActionType() {
        Page<AuditLogDto> page = service.search(AuditActionType.ORDER_DECLINED, null, null, null,
                PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals("ORDER_DECLINED", page.getContent().get(0).actionType());
    }

    @Test
    void filtersByActorId() {
        Page<AuditLogDto> page = service.search(null, 8L, null, null, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals(8L, page.getContent().get(0).actorId());
    }

    @Test
    void filtersByDateRange() {
        Page<AuditLogDto> page = service.search(null, null,
                LocalDateTime.of(2026, 5, 12, 0, 0),
                LocalDateTime.of(2026, 5, 16, 0, 0), PageRequest.of(0, 10));

        assertEquals(2, page.getTotalElements(), "samo 15.5 redovi u opsegu 12-16.5");
    }

    @Test
    void combinesAllFilters() {
        Page<AuditLogDto> page = service.search(AuditActionType.ORDER_APPROVED, 7L,
                LocalDateTime.of(2026, 5, 12, 0, 0),
                LocalDateTime.of(2026, 5, 25, 0, 0), PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals(LocalDateTime.of(2026, 5, 20, 9, 0), page.getContent().get(0).createdAt());
    }

    @Test
    void paginates() {
        Page<AuditLogDto> first = service.search(null, null, null, null,
                PageRequest.of(0, 2, Sort.by(Sort.Direction.DESC, "createdAt")));

        assertEquals(4, first.getTotalElements());
        assertEquals(2, first.getContent().size());
        assertEquals(2, first.getTotalPages());
    }
}
