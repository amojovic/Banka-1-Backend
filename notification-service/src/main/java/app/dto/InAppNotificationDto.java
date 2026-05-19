package app.dto;

import app.entities.InAppNotification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Read-only projection of an {@link InAppNotification} returned by the REST API.
 *
 * <p>The DTO deliberately omits {@code recipientUserId}/{@code recipientType}:
 * every endpoint is already scoped to the authenticated caller, so echoing the
 * owner back adds no value and would leak the internal id space shape.
 *
 * @param id          surrogate identifier of the notification
 * @param type        notification type resolved from the RabbitMQ routing key
 * @param title       short user-facing headline
 * @param body        full user-facing message
 * @param read        whether the owner has marked the notification read
 * @param referenceId optional deep-link reference to the originating object
 * @param createdAt   creation timestamp used for newest-first ordering
 */
@Schema(description = "In-app notification shown in the web client feed")
public record InAppNotificationDto(
        Long id,
        String type,
        String title,
        String body,
        boolean read,
        String referenceId,
        Instant createdAt
) {

    /**
     * Maps a persisted entity into its API projection.
     *
     * @param entity persisted notification, never {@code null}
     * @return immutable DTO view of the entity
     */
    public static InAppNotificationDto from(InAppNotification entity) {
        return new InAppNotificationDto(
                entity.getId(),
                entity.getType(),
                entity.getTitle(),
                entity.getBody(),
                entity.isRead(),
                entity.getReferenceId(),
                entity.getCreatedAt()
        );
    }
}
