package com.banka1.tradingservice.audit.listener;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import com.banka1.tradingservice.audit.dto.AuditEventDto;
import com.banka1.tradingservice.audit.repository.AuditLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WP-2: unit test za {@link AuditEventListener} — verifikuje persistovanje
 * validnih dogadjaja i bezbedno preskakanje nevalidnih (listener ne puca).
 */
@ExtendWith(MockitoExtension.class)
class AuditEventListenerTest {

    @Mock
    private AuditLogRepository repository;

    @InjectMocks
    private AuditEventListener listener;

    @Test
    void persistsValidEvent() {
        long epochMillis = 1_747_000_000_000L;
        AuditEventDto event = new AuditEventDto(
                7L, "Marko Markovic", "ORDER_APPROVED", "ORDER", "42",
                "old=PENDING, new=APPROVED", epochMillis);

        listener.onAuditEvent(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        AuditLog saved = captor.getValue();
        assertEquals(7L, saved.getActorId());
        assertEquals("Marko Markovic", saved.getActorName());
        assertEquals(AuditActionType.ORDER_APPROVED, saved.getActionType());
        assertEquals("ORDER", saved.getTargetType());
        assertEquals("42", saved.getTargetId());
        assertEquals(LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()),
                saved.getCreatedAt());
    }

    @Test
    void skipsEventWithUnknownActionType() {
        AuditEventDto event = new AuditEventDto(
                7L, "Marko", "NONEXISTENT_ACTION", "ORDER", "42", "d", null);

        listener.onAuditEvent(event);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsEventWithNullActionType() {
        AuditEventDto event = new AuditEventDto(
                7L, "Marko", null, "ORDER", "42", "d", null);

        listener.onAuditEvent(event);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skipsNullPayload() {
        listener.onAuditEvent(null);

        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void usesNullActorIdForSystemEvent() {
        AuditEventDto event = new AuditEventDto(
                null, "SYSTEM", "TAX_RUN_SCHEDULED", "TAX", "2026-05", "cron run", null);

        listener.onAuditEvent(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getActorId(), "SYSTEM aktor -> actorId null");
        assertEquals(AuditActionType.TAX_RUN_SCHEDULED, captor.getValue().getActionType());
    }

    @Test
    void fallsBackToNowWhenTimestampNull() {
        LocalDateTime before = LocalDateTime.now();
        AuditEventDto event = new AuditEventDto(
                1L, "A", "ORDER_DECLINED", "ORDER", "1", "d", null);

        listener.onAuditEvent(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(captor.capture());
        LocalDateTime createdAt = captor.getValue().getCreatedAt();
        org.junit.jupiter.api.Assertions.assertNotNull(createdAt);
        org.junit.jupiter.api.Assertions.assertTrue(
                !createdAt.isBefore(before.minusSeconds(1)),
                "null timestamp -> trenutak prijema");
    }
}
