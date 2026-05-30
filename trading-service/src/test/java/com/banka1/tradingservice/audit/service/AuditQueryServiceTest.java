package com.banka1.tradingservice.audit.service;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import com.banka1.tradingservice.audit.dto.AuditLogDto;
import com.banka1.tradingservice.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-2: unit test za {@link AuditQueryService} — verifikuje mapiranje
 * entitet -> DTO i prosledjivanje {@link Specification}/{@link Pageable}.
 */
@ExtendWith(MockitoExtension.class)
class AuditQueryServiceTest {

    @Mock
    private AuditLogRepository repository;

    @InjectMocks
    private AuditQueryService service;

    private AuditLog entity(Long id, AuditActionType type, Long actorId) {
        return AuditLog.builder()
                .id(id)
                .actorId(actorId)
                .actorName(actorId == null ? "SYSTEM" : "Actor-" + actorId)
                .actionType(type)
                .targetType("ORDER")
                .targetId("100")
                .details("d")
                .createdAt(LocalDateTime.of(2026, 5, 18, 12, 0))
                .build();
    }

    @Test
    void mapsEntityPageToDtoPage() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditLog> entityPage = new PageImpl<>(
                List.of(entity(1L, AuditActionType.ORDER_APPROVED, 7L)), pageable, 1);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(entityPage);

        Page<AuditLogDto> result = service.search(
                AuditActionType.ORDER_APPROVED, 7L, null, null, pageable);

        assertEquals(1, result.getTotalElements());
        AuditLogDto dto = result.getContent().get(0);
        assertEquals(1L, dto.id());
        assertEquals("ORDER_APPROVED", dto.actionType());
        assertEquals(7L, dto.actorId());
        assertEquals("Actor-7", dto.actorName());
    }

    @Test
    void handlesNullActorIdInDtoMapping() {
        Pageable pageable = PageRequest.of(0, 20);
        Page<AuditLog> entityPage = new PageImpl<>(
                List.of(entity(2L, AuditActionType.TAX_RUN_SCHEDULED, null)), pageable, 1);
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(entityPage);

        Page<AuditLogDto> result = service.search(null, null, null, null, pageable);

        assertNull(result.getContent().get(0).actorId(), "SYSTEM aktor -> actorId null");
    }

    @Test
    void passesSpecificationAndPageableToRepository() {
        Pageable pageable = PageRequest.of(2, 5);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        service.search(AuditActionType.ORDER_DECLINED, 9L,
                LocalDateTime.of(2026, 5, 1, 0, 0),
                LocalDateTime.of(2026, 5, 31, 0, 0), pageable);

        ArgumentCaptor<Specification> specCaptor = ArgumentCaptor.forClass(Specification.class);
        ArgumentCaptor<Pageable> pageCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(specCaptor.capture(), pageCaptor.capture());

        assertNotNull(specCaptor.getValue(), "specifikacija mora biti prosledjena");
        assertEquals(2, pageCaptor.getValue().getPageNumber());
        assertEquals(5, pageCaptor.getValue().getPageSize());
    }

    @Test
    void emptyResultYieldsEmptyPage() {
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), pageable, 0));

        Page<AuditLogDto> result = service.search(null, null, null, null, pageable);

        assertEquals(0, result.getTotalElements());
    }
}
