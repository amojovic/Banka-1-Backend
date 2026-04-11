package app.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link FcmPushService}.
 *
 * <p>Since the test JVM never initializes a real {@link com.google.firebase.FirebaseApp},
 * the service instance constructed here runs in its "email-only" degraded mode where
 * {@code firebaseAvailable} is false. These tests protect that short-circuit path, which
 * is the contract the rest of the delivery pipeline relies on: a missing Firebase
 * configuration must never block email delivery or propagate exceptions up to
 * {@link NotificationDeliveryService}.
 *
 * <p>The happy path that actually talks to {@code FirebaseMessaging} is deliberately
 * not exercised here to keep the unit test hermetic — it is covered end-to-end through
 * Postman against a running Firebase project.
 */
class FcmPushServiceUnitTest {

    /**
     * Verifies that constructing the service without an initialized Firebase SDK
     * does not throw and leaves the service in a callable state.
     *
     * <p>This is the baseline guarantee for every environment where
     * {@code FIREBASE_CREDENTIALS_PATH} is intentionally unset, such as CI.
     */
    @Test
    void constructorSucceedsWhenFirebaseIsNotInitialized() {
        assertDoesNotThrow((org.junit.jupiter.api.function.ThrowingSupplier<FcmPushService>) FcmPushService::new);
    }

    /**
     * Verifies that sendVerificationPush short-circuits cleanly when Firebase is not
     * available, accepting all parameters (including nulls for optional fields) without
     * throwing.
     *
     * <p>This protects the fire-and-forget contract enforced by
     * {@link NotificationDeliveryService#attemptFcmPush} — the email branch must stay
     * authoritative even if FCM is fully disabled.
     */
    @Test
    void sendVerificationPushDoesNothingWhenFirebaseIsUnavailable() {
        FcmPushService service = new FcmPushService();

        assertDoesNotThrow(() ->
                service.sendVerificationPush("fcm-token", "123456", "PAYMENT", "42")
        );
    }

    /**
     * Verifies that sendVerificationPush tolerates null operationType and sessionId
     * parameters even in the short-circuit path.
     *
     * <p>The message builder substitutes defaults for these fields, so callers are
     * allowed to pass nulls. The test guards against a regression that would
     * re-introduce an NPE for partial payloads.
     */
    @Test
    void sendVerificationPushHandlesNullOptionalFieldsWithoutThrowing() {
        FcmPushService service = new FcmPushService();

        assertDoesNotThrow(() ->
                service.sendVerificationPush("fcm-token", "123456", null, null)
        );
    }
}