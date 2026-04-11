package app.service;

import app.entities.FcmToken;
import app.repository.FcmTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Manages the {@link FcmToken} registry that maps a {@code clientId} to a single
 * active Firebase Cloud Messaging device token.
 *
 * <p>The lifecycle mirrors the mobile app: a token is inserted or refreshed on
 * login, looked up when a verification push must be delivered, and removed on
 * logout or explicit deregistration. Only one active token is kept per client
 * — re-registering with a different token replaces the previous value rather
 * than creating a parallel row, because a client is expected to have a single
 * active mobile device at a time in this project's scope.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FcmTokenService {

    /**
     * Persistence entry point for {@link FcmToken} records.
     */
    private final FcmTokenRepository fcmTokenRepository;

    /**
     * Registers a new FCM token for the given client or refreshes the existing
     * one in place.
     *
     * <p>If a row already exists for {@code clientId}, its token value and
     * {@code updatedAt} timestamp are overwritten. This upsert semantics matches
     * the mobile app's behavior of calling {@code PUT /notifications/fcm/token}
     * on every login regardless of whether the token changed.
     *
     * @param clientId id of the client whose device is being registered
     * @param token the FCM device token produced by the Firebase SDK on the
     *              mobile client
     */
    @Transactional
    public void upsertToken(Long clientId, String token) {
        Optional<FcmToken> existing = fcmTokenRepository.findByClientId(clientId);
        if (existing.isPresent()) {
            FcmToken entity = existing.get();
            entity.setFcmToken(token);
            entity.setUpdatedAt(Instant.now());
            fcmTokenRepository.save(entity);
            log.info("Updated FCM token for clientId={}", clientId);
        } else {
            FcmToken entity = FcmToken.builder()
                    .clientId(clientId)
                    .fcmToken(token)
                    .updatedAt(Instant.now())
                    .build();
            fcmTokenRepository.save(entity);
            log.info("Registered new FCM token for clientId={}", clientId);
        }
    }

    /**
     * Returns the currently registered FCM token for the given client, if any.
     *
     * <p>Used by {@link NotificationDeliveryService} when a verification OTP
     * event must be mirrored to a mobile push: an empty {@link Optional} simply
     * means the client has no mobile device registered and the push is skipped.
     *
     * @param clientId id of the client to look up
     * @return the registered token or {@link Optional#empty()} when the client
     *         has never registered a device or was explicitly deregistered
     */
    public Optional<String> findToken(Long clientId) {
        return fcmTokenRepository.findByClientId(clientId)
                .map(FcmToken::getFcmToken);
    }

    /**
     * Removes the FCM token registration for the given client.
     *
     * <p>Called on explicit logout from the mobile app. Absent rows are a
     * silent no-op — Spring Data's {@code deleteByClientId} translates to a
     * single DELETE statement that affects zero rows when nothing is registered.
     *
     * @param clientId id of the client whose device is being deregistered
     */
    @Transactional
    public void deleteToken(Long clientId) {
        fcmTokenRepository.deleteByClientId(clientId);
        log.info("Deleted FCM token for clientId={}", clientId);
    }
}
