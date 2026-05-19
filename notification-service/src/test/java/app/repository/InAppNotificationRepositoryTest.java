package app.repository;

import app.entities.InAppNotification;
import app.entities.RecipientType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Data slice test for {@link InAppNotificationRepository}.
 *
 * <p>Runs against H2 in PostgreSQL mode with the schema built from the JPA
 * entities ({@code ddl-auto=create-drop}); Flyway is disabled in the test
 * profile. The test protects the owner-scoping invariants — no query may cross
 * the {@code (recipientUserId, recipientType)} boundary — and the newest-first
 * ordering contract of the feed.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InAppNotificationRepositoryTest {

    @Autowired
    private InAppNotificationRepository repository;

    private InAppNotification notification(Long userId, RecipientType type,
                                           boolean read, Instant createdAt) {
        return InAppNotification.builder()
                .recipientUserId(userId)
                .recipientType(type)
                .type("TRANSACTION_COMPLETED")
                .title("Transakcija")
                .body("Vasa transakcija je izvrsena.")
                .read(read)
                .referenceId("tx-1")
                .createdAt(createdAt)
                .build();
    }

    @Test
    void persistsAndGeneratesIdentity() {
        InAppNotification saved = repository.save(
                notification(1L, RecipientType.CLIENT, false, Instant.now()));

        assertNotNull(saved.getId());
        assertFalse(saved.isRead());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void prePersistStampsCreatedAtWhenAbsent() {
        InAppNotification entity = notification(1L, RecipientType.CLIENT, false, null);

        InAppNotification saved = repository.save(entity);

        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void feedIsScopedToOwnerAndOrderedNewestFirst() {
        Instant base = Instant.now();
        repository.save(notification(1L, RecipientType.CLIENT, false,
                base.minus(2, ChronoUnit.HOURS)));
        repository.save(notification(1L, RecipientType.CLIENT, false, base));
        repository.save(notification(1L, RecipientType.CLIENT, false,
                base.minus(1, ChronoUnit.HOURS)));
        // Same numeric id but EMPLOYEE space — must not leak into the client feed.
        repository.save(notification(1L, RecipientType.EMPLOYEE, false, base));
        // Different client — must not leak either.
        repository.save(notification(2L, RecipientType.CLIENT, false, base));

        Page<InAppNotification> page = repository
                .findByRecipientUserIdAndRecipientTypeOrderByCreatedAtDesc(
                        1L, RecipientType.CLIENT, PageRequest.of(0, 10));

        assertEquals(3, page.getTotalElements());
        List<InAppNotification> content = page.getContent();
        assertTrue(content.get(0).getCreatedAt()
                .isAfter(content.get(1).getCreatedAt()));
        assertTrue(content.get(1).getCreatedAt()
                .isAfter(content.get(2).getCreatedAt()));
    }

    @Test
    void unreadCountIsScopedToOwner() {
        repository.save(notification(1L, RecipientType.CLIENT, false, Instant.now()));
        repository.save(notification(1L, RecipientType.CLIENT, false, Instant.now()));
        repository.save(notification(1L, RecipientType.CLIENT, true, Instant.now()));
        repository.save(notification(1L, RecipientType.EMPLOYEE, false, Instant.now()));
        repository.save(notification(2L, RecipientType.CLIENT, false, Instant.now()));

        long count = repository
                .countByRecipientUserIdAndRecipientTypeAndReadFalse(1L, RecipientType.CLIENT);

        assertEquals(2, count);
    }

    @Test
    void findByIdScopedReturnsOnlyOwnedNotification() {
        InAppNotification owned = repository.save(
                notification(1L, RecipientType.CLIENT, false, Instant.now()));

        Optional<InAppNotification> hit = repository
                .findByIdAndRecipientUserIdAndRecipientType(
                        owned.getId(), 1L, RecipientType.CLIENT);
        Optional<InAppNotification> wrongUser = repository
                .findByIdAndRecipientUserIdAndRecipientType(
                        owned.getId(), 2L, RecipientType.CLIENT);
        Optional<InAppNotification> wrongType = repository
                .findByIdAndRecipientUserIdAndRecipientType(
                        owned.getId(), 1L, RecipientType.EMPLOYEE);

        assertTrue(hit.isPresent());
        assertTrue(wrongUser.isEmpty());
        assertTrue(wrongType.isEmpty());
    }

    @Test
    void markAllReadAffectsOnlyOwnerUnreadRows() {
        repository.save(notification(1L, RecipientType.CLIENT, false, Instant.now()));
        repository.save(notification(1L, RecipientType.CLIENT, false, Instant.now()));
        repository.save(notification(1L, RecipientType.CLIENT, true, Instant.now()));
        repository.save(notification(1L, RecipientType.EMPLOYEE, false, Instant.now()));
        repository.save(notification(2L, RecipientType.CLIENT, false, Instant.now()));

        int updated = repository.markAllReadForRecipient(1L, RecipientType.CLIENT);

        assertEquals(2, updated);
        assertEquals(0, repository
                .countByRecipientUserIdAndRecipientTypeAndReadFalse(1L, RecipientType.CLIENT));
        assertEquals(1, repository
                .countByRecipientUserIdAndRecipientTypeAndReadFalse(1L, RecipientType.EMPLOYEE));
        assertEquals(1, repository
                .countByRecipientUserIdAndRecipientTypeAndReadFalse(2L, RecipientType.CLIENT));
    }
}
