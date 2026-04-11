package app.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA entity mapping the {@code fcm_tokens} table that tracks the active
 * Firebase Cloud Messaging device token for each client.
 *
 * <p>The {@code clientId} column carries a unique constraint because the
 * project intentionally allows at most one active device per client — upserting
 * through {@link app.service.FcmTokenService#upsertToken(Long, String)} is the
 * only supported way to mutate this table.
 */
@Entity
@Table(name = "fcm_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FcmToken {

    /** Surrogate primary key. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Client id that owns this registration. Unique — re-registration replaces
     * the existing row rather than inserting a parallel one.
     */
    @Column(nullable = false, unique = true)
    private Long clientId;

    /**
     * Raw FCM device token as produced by the Firebase SDK on the mobile client.
     * The 512 character cap comfortably fits the current token shapes produced
     * by the Firebase Android SDK.
     */
    @Column(nullable = false, length = 512)
    private String fcmToken;

    /**
     * Wall-clock time of the last upsert, used mainly for observability and
     * future stale-token cleanup.
     */
    @Column(nullable = false)
    private Instant updatedAt;
}
