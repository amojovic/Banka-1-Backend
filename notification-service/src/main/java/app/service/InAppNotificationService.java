package app.service;

import app.dto.InAppNotificationDto;
import app.entities.InAppNotification;
import app.entities.RecipientType;
import app.repository.InAppNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Application service backing the in-app notification feed.
 *
 * <p>It owns two responsibilities:
 * <ul>
 *   <li>the read/mutate surface used by the web client through
 *       {@link app.controller.InAppNotificationController} — always scoped to
 *       the authenticated caller;</li>
 *   <li>the write path used by {@link NotificationDeliveryService} to persist
 *       one in-app row per consumed RabbitMQ message that carries a resolvable
 *       recipient.</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InAppNotificationService {

    /** Persistence entry point for {@link InAppNotification} rows. */
    private final InAppNotificationRepository repository;

    /**
     * Returns one page of the caller's notifications, newest first.
     *
     * <p>The supplied {@code page}/{@code size} are honoured but the sort is
     * fixed to {@code createdAt DESC} — the feed contract is non-negotiable, so
     * the repository derived query (not the caller) owns the ordering.
     *
     * @param userId        caller id from the JWT {@code id} claim
     * @param recipientType caller id space discriminator
     * @param page          zero-based page index
     * @param size          page size
     * @return page of notification DTOs scoped to the caller
     */
    @Transactional(readOnly = true)
    public Page<InAppNotificationDto> getNotifications(Long userId,
                                                       RecipientType recipientType,
                                                       int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return repository
                .findByRecipientUserIdAndRecipientTypeOrderByCreatedAtDesc(
                        userId, recipientType, pageable)
                .map(InAppNotificationDto::from);
    }

    /**
     * Counts the caller's unread notifications.
     *
     * @param userId        caller id from the JWT {@code id} claim
     * @param recipientType caller id space discriminator
     * @return number of unread notifications owned by the caller
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId, RecipientType recipientType) {
        return repository
                .countByRecipientUserIdAndRecipientTypeAndReadFalse(userId, recipientType);
    }

    /**
     * Marks a single notification read, but only when it belongs to the caller.
     *
     * @param id            notification primary key
     * @param userId        caller id from the JWT {@code id} claim
     * @param recipientType caller id space discriminator
     * @return {@code true} when a notification owned by the caller was found
     *         and updated, {@code false} when nothing matched
     */
    @Transactional
    public boolean markRead(Long id, Long userId, RecipientType recipientType) {
        Optional<InAppNotification> found = repository
                .findByIdAndRecipientUserIdAndRecipientType(id, userId, recipientType);
        if (found.isEmpty()) {
            return false;
        }
        InAppNotification notification = found.get();
        if (!notification.isRead()) {
            notification.setRead(true);
            repository.save(notification);
        }
        return true;
    }

    /**
     * Marks every unread notification owned by the caller as read.
     *
     * @param userId        caller id from the JWT {@code id} claim
     * @param recipientType caller id space discriminator
     * @return number of notifications updated
     */
    @Transactional
    public int markAllRead(Long userId, RecipientType recipientType) {
        return repository.markAllReadForRecipient(userId, recipientType);
    }

    /**
     * Persists a new unread in-app notification.
     *
     * <p>Invoked from the RabbitMQ consumer path. The call is best-effort from
     * the consumer's perspective: a missing/invalid recipient simply skips the
     * row and email delivery still proceeds. Any persistence failure here is
     * caught and logged so it never aborts the surrounding delivery flow.
     *
     * @param recipientUserId owner id carried by the incoming payload
     * @param recipientTypeRaw raw recipient type string from the payload
     * @param type            notification type resolved from the routing key
     * @param title           rendered email subject reused as the headline
     * @param body            rendered email body reused as the message
     * @param referenceId     optional deep-link reference, may be {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createForRecipient(Long recipientUserId, String recipientTypeRaw,
                                   String type, String title, String body,
                                   String referenceId) {
        if (recipientUserId == null) {
            log.debug("No recipientUserId on message type={}, skipping in-app row", type);
            return;
        }
        RecipientType recipientType = parseRecipientType(recipientTypeRaw);
        if (recipientType == null) {
            log.debug("Unresolvable recipientType='{}' on message type={}, skipping in-app row",
                    recipientTypeRaw, type);
            return;
        }
        try {
            InAppNotification entity = InAppNotification.builder()
                    .recipientUserId(recipientUserId)
                    .recipientType(recipientType)
                    .type(type)
                    .title(title != null ? title : type)
                    .body(body != null ? body : "")
                    .read(false)
                    .referenceId(referenceId)
                    .build();
            repository.save(entity);
            log.debug("Created in-app notification type={} for {} {}",
                    type, recipientType, recipientUserId);
        } catch (Exception ex) {
            log.warn("Failed to persist in-app notification type={} for userId={}: {}",
                    type, recipientUserId, ex.getMessage());
        }
    }

    /**
     * Parses a raw recipient-type string into the {@link RecipientType} enum.
     *
     * @param raw recipient type string from the payload, may be {@code null}
     * @return matching enum value, or {@code null} when blank/unrecognized
     */
    private RecipientType parseRecipientType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RecipientType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
