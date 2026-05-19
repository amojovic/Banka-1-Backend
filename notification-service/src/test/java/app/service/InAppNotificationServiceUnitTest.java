package app.service;

import app.dto.InAppNotificationDto;
import app.entities.InAppNotification;
import app.entities.RecipientType;
import app.repository.InAppNotificationRepository;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InAppNotificationService}.
 *
 * <p>The repository is mocked; the tests protect the service contract: paging
 * delegation, owner-scoped read paths, ownership-guarded single mark-read, the
 * skip-if-already-read optimization, and the graceful no-op / swallow-failure
 * behaviour of the consumer write path.
 */
@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceUnitTest {

    @Mock
    private InAppNotificationRepository repository;

    @InjectMocks
    private InAppNotificationService service;

    private InAppNotification entity(Long id, boolean read) {
        return InAppNotification.builder()
                .id(id)
                .recipientUserId(1L)
                .recipientType(RecipientType.CLIENT)
                .type("TRANSACTION_COMPLETED")
                .title("Transakcija")
                .body("Telo")
                .read(read)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void getNotificationsDelegatesWithFixedSortAndMapsToDto() {
        Page<InAppNotification> page = new PageImpl<>(
                List.of(entity(5L, false)), PageRequest.of(0, 20), 1);
        when(repository.findByRecipientUserIdAndRecipientTypeOrderByCreatedAtDesc(
                eq(1L), eq(RecipientType.CLIENT), any(Pageable.class))).thenReturn(page);

        Page<InAppNotificationDto> result =
                service.getNotifications(1L, RecipientType.CLIENT, 0, 20);

        assertEquals(1, result.getTotalElements());
        assertEquals(5L, result.getContent().get(0).id());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByRecipientUserIdAndRecipientTypeOrderByCreatedAtDesc(
                eq(1L), eq(RecipientType.CLIENT), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(20, captor.getValue().getPageSize());
    }

    @Test
    void unreadCountDelegatesToRepository() {
        when(repository.countByRecipientUserIdAndRecipientTypeAndReadFalse(
                1L, RecipientType.EMPLOYEE)).thenReturn(7L);

        assertEquals(7L, service.unreadCount(1L, RecipientType.EMPLOYEE));
    }

    @Test
    void markReadFlipsUnreadOwnedNotification() {
        InAppNotification owned = entity(5L, false);
        when(repository.findByIdAndRecipientUserIdAndRecipientType(
                5L, 1L, RecipientType.CLIENT)).thenReturn(Optional.of(owned));

        boolean result = service.markRead(5L, 1L, RecipientType.CLIENT);

        assertTrue(result);
        assertTrue(owned.isRead());
        verify(repository).save(owned);
    }

    @Test
    void markReadIsNoSaveWhenAlreadyRead() {
        InAppNotification owned = entity(5L, true);
        when(repository.findByIdAndRecipientUserIdAndRecipientType(
                5L, 1L, RecipientType.CLIENT)).thenReturn(Optional.of(owned));

        boolean result = service.markRead(5L, 1L, RecipientType.CLIENT);

        assertTrue(result);
        verify(repository, never()).save(any());
    }

    @Test
    void markReadReturnsFalseWhenNotOwned() {
        when(repository.findByIdAndRecipientUserIdAndRecipientType(
                5L, 1L, RecipientType.CLIENT)).thenReturn(Optional.empty());

        boolean result = service.markRead(5L, 1L, RecipientType.CLIENT);

        assertFalse(result);
        verify(repository, never()).save(any());
    }

    @Test
    void markAllReadDelegatesAndReturnsCount() {
        when(repository.markAllReadForRecipient(1L, RecipientType.CLIENT)).thenReturn(3);

        assertEquals(3, service.markAllRead(1L, RecipientType.CLIENT));
    }

    @Test
    void createForRecipientPersistsRowForValidInput() {
        service.createForRecipient(99L, "CLIENT", "ORDER_EXECUTED",
                "Nalog izvrsen", "Telo", "order-1");

        ArgumentCaptor<InAppNotification> captor =
                ArgumentCaptor.forClass(InAppNotification.class);
        verify(repository).save(captor.capture());
        InAppNotification saved = captor.getValue();
        assertEquals(99L, saved.getRecipientUserId());
        assertEquals(RecipientType.CLIENT, saved.getRecipientType());
        assertEquals("ORDER_EXECUTED", saved.getType());
        assertEquals("Nalog izvrsen", saved.getTitle());
        assertEquals("order-1", saved.getReferenceId());
        assertFalse(saved.isRead());
    }

    @Test
    void createForRecipientAcceptsLowercaseRecipientType() {
        service.createForRecipient(99L, " employee ", "ACCOUNT_CREATED",
                "Racun", "Telo", null);

        ArgumentCaptor<InAppNotification> captor =
                ArgumentCaptor.forClass(InAppNotification.class);
        verify(repository).save(captor.capture());
        assertEquals(RecipientType.EMPLOYEE, captor.getValue().getRecipientType());
    }

    @Test
    void createForRecipientSkipsWhenUserIdNull() {
        service.createForRecipient(null, "CLIENT", "ORDER_EXECUTED", "T", "B", null);

        verify(repository, never()).save(any());
    }

    @Test
    void createForRecipientSkipsWhenRecipientTypeUnresolvable() {
        service.createForRecipient(99L, "ROBOT", "ORDER_EXECUTED", "T", "B", null);
        service.createForRecipient(99L, null, "ORDER_EXECUTED", "T", "B", null);
        service.createForRecipient(99L, "  ", "ORDER_EXECUTED", "T", "B", null);

        verify(repository, never()).save(any());
    }

    @Test
    void createForRecipientSwallowsPersistenceFailure() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        // Must not propagate — email delivery stays authoritative.
        service.createForRecipient(99L, "CLIENT", "ORDER_EXECUTED", "T", "B", null);

        verify(repository).save(any());
    }

    @Test
    void createForRecipientDefaultsNullTitleAndBody() {
        service.createForRecipient(99L, "CLIENT", "DIVIDEND_PAID", null, null, null);

        ArgumentCaptor<InAppNotification> captor =
                ArgumentCaptor.forClass(InAppNotification.class);
        verify(repository).save(captor.capture());
        assertEquals("DIVIDEND_PAID", captor.getValue().getTitle());
        assertEquals("", captor.getValue().getBody());
    }
}
