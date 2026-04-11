package app.repository;

import app.entities.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Spring Data repository for {@link FcmToken} rows in the {@code fcm_tokens}
 * table.
 *
 * <p>Only two access paths exist beyond the standard {@code JpaRepository}
 * surface because all FCM token lookups in this service are keyed by
 * {@code clientId} — which is the unique constraint on the table.
 */
@Repository
public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    /**
     * Finds the active FCM registration for a client.
     *
     * @param clientId id of the client to look up
     * @return the registration if one exists, otherwise empty
     */
    Optional<FcmToken> findByClientId(Long clientId);

    /**
     * Removes the registration for a client, if any. Absent rows are a silent
     * no-op.
     *
     * @param clientId id of the client to deregister
     */
    void deleteByClientId(Long clientId);
}
