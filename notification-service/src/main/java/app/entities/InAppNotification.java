package app.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persistent in-app notification shown inside the web client.
 *
 * <p>This entity is intentionally separate from {@link NotificationDelivery}:
 * the latter is an email delivery/retry audit log with no user FK and no read
 * flag, so it cannot back a per-user notification feed. One row of this entity
 * is created for every consumed RabbitMQ message that carries a resolvable
 * recipient ({@code recipientUserId} + {@code recipientType}); messages without
 * a recipient still trigger email delivery but produce no in-app row.
 *
 * <p>The schema is created explicitly via Flyway migration
 * {@code V2__in_app_notifications.sql}; Hibernate {@code ddl-auto=update} only
 * supplements it and never owns it.
 */
@Entity
@Table(
        name = "in_app_notifications",
        indexes = {
                @Index(
                        name = "idx_in_app_notifications_recipient",
                        columnList = "recipient_user_id, recipient_type, created_at"
                ),
                @Index(
                        name = "idx_in_app_notifications_unread",
                        columnList = "recipient_user_id, recipient_type, read"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InAppNotification {

    /** Surrogate primary key (BIGSERIAL). */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Identifier of the user who owns this notification. Combined with
     * {@link #recipientType} it scopes every read path so a caller can only see
     * and mutate its own notifications.
     */
    @Column(name = "recipient_user_id", nullable = false)
    private Long recipientUserId;

    /** Discriminates the {@link #recipientUserId} id space (client vs employee). */
    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false, length = 16)
    private RecipientType recipientType;

    /** Notification type resolved from the originating RabbitMQ routing key. */
    @Column(nullable = false, length = 64)
    private String type;

    /** Short, user-facing headline rendered from the configured template subject. */
    @Column(nullable = false)
    private String title;

    /** Full, user-facing message rendered from the configured template body. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * Read state of the notification. New rows are always unread; the flag flips
     * to {@code true} when the owner explicitly marks the notification read.
     */
    @Column(nullable = false)
    private boolean read;

    /**
     * Optional opaque reference to the originating domain object (transaction id,
     * order id, contract id, ...). Lets the web client deep-link from the feed.
     */
    @Column(name = "reference_id")
    private String referenceId;

    /** Creation timestamp; the feed is ordered newest-first on this column. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Stamps {@link #createdAt} before first persistence when it was not set
     * explicitly by the caller.
     */
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
