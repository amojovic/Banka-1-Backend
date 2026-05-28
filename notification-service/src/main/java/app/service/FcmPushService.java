package app.service;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidConfig.Priority;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Sends verification OTP pushes to the mobile client via Firebase Cloud Messaging.
 *
 * <p>This service is deliberately decoupled from the main delivery pipeline:
 * <ul>
 *   <li>Email delivery remains the authoritative channel for verification codes
 *       and is handled by {@link NotificationDeliveryService}.</li>
 *   <li>FCM pushes are an additive convenience that lets the mobile app display
 *       the code in a notification. If Firebase is not initialized or the send
 *       fails for any reason the rest of the pipeline is unaffected.</li>
 * </ul>
 *
 * <p>The service captures the initialization state of {@link FirebaseApp} at
 * construction time. In environments where {@code FIREBASE_CREDENTIALS_PATH} is
 * not set {@link FirebaseConfig} never creates a {@link FirebaseApp}, and every
 * call here becomes a no-op.
 */
@Service
@Slf4j
public class FcmPushService {

    /**
     * True when a {@link FirebaseApp} was successfully initialized before this
     * service was constructed. Checked on each send to short-circuit cleanly in
     * email-only deployments.
     */
    private final boolean firebaseAvailable;

    /**
     * Captures the availability of Firebase at bean construction time.
     * Logs a single warning if Firebase has not been initialized so that
     * operators immediately notice the degraded mode in service logs.
     */
    public FcmPushService() {
        this.firebaseAvailable = !FirebaseApp.getApps().isEmpty();
        if (!firebaseAvailable) {
            log.warn("Firebase is not initialized — FCM push will be skipped");
        }
    }

    /**
     * Sends a high-priority data-only FCM message containing a verification OTP
     * to a single device.
     *
     * <p>The payload carries {@code type=VERIFICATION_OTP} together with the
     * verification code, operation type, and session id so the mobile app can
     * render the notification, store the code locally, and later validate it via
     * the verification-service. No notification block is included — the mobile
     * app is responsible for building the user-visible notification from the
     * data payload so it behaves consistently regardless of app lifecycle state.
     *
     * <p>Failures never propagate: any exception thrown by the FCM SDK is logged
     * at warn level and swallowed so that email delivery (the authoritative
     * channel) is not disturbed.
     *
     * @param fcmToken device token previously registered via
     *                 {@link FcmTokenService#upsertToken(Long, String)}
     * @param code raw 6-digit OTP code to display on the device
     * @param operationType business operation triggering the verification, e.g.
     *                      {@code PAYMENT} or {@code TRANSFER}; {@code UNKNOWN}
     *                      is substituted when null
     * @param sessionId verification-service session id used by the mobile app
     *                  when calling {@code /verification/validate}; empty string
     *                  is substituted when null
     */
    public void sendVerificationPush(String fcmToken, String code,
                                      String operationType, String sessionId) {
        if (!firebaseAvailable) {
            log.debug("Firebase not available, skipping FCM push");
            return;
        }

        Message message = Message.builder()
                .setToken(fcmToken)
                .putData("type", "VERIFICATION_OTP")
                .putData("code", code)
                .putData("operationType", operationType != null ? operationType : "UNKNOWN")
                .putData("sessionId", sessionId != null ? sessionId : "")
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(Priority.HIGH)
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM push sent: messageId={}, operationType={}", messageId, operationType);
        } catch (Exception e) {
            log.warn("FCM push failed (email delivery is authoritative): {}", e.getMessage());
        }
    }

    /**
     * Sends a high-priority data-only FCM message for a price alert trigger.
     *
     * <p>Mirrors {@link #sendVerificationPush} in structure. Failures are swallowed
     * so that email delivery (the authoritative channel) is not disturbed.
     *
     * @param fcmToken device token for the alert owner
     * @param type notification type constant, e.g. {@code PRICE_ALERT_TRIGGERED}
     * @param title notification title rendered by the mobile app
     * @param body notification body rendered by the mobile app
     * @param vars template variables; keys read: {@code ticker}, {@code threshold},
     *             {@code price} (mapped to {@code triggeredPrice}), {@code condition}
     */
    public void sendPriceAlertPush(String fcmToken, String type, String title, String body,
                                    Map<String, String> vars) {
        if (!firebaseAvailable) {
            log.debug("Firebase not available, skipping FCM price alert push");
            return;
        }

        Message message = Message.builder()
                .setToken(fcmToken)
                .putData("type", type != null ? type : "PRICE_ALERT_TRIGGERED")
                .putData("title", title != null ? title : "")
                .putData("body", body != null ? body : "")
                .putData("ticker", vars.getOrDefault("ticker", ""))
                .putData("threshold", vars.getOrDefault("threshold", ""))
                .putData("triggeredPrice", vars.getOrDefault("price", ""))
                .putData("condition", vars.getOrDefault("condition", ""))
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(Priority.HIGH)
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM price alert push sent: messageId={}, ticker={}", messageId, vars.get("ticker"));
        } catch (Exception e) {
            log.warn("FCM price alert push failed: {}", e.getMessage());
        }
    }

    /**
     * Sends a high-priority data-only FCM message describing an order lifecycle event.
     *
     * <p>The payload carries {@code type=ORDER_<EVENT>} (e.g. {@code ORDER_DONE}) plus a
     * human-readable {@code title}/{@code body} and the key order fields ({@code orderId},
     * {@code ticker}, {@code status}) so the mobile app can persist an inbox row and render a
     * system notification consistently regardless of app lifecycle state.
     *
     * <p>Failures never propagate: any exception thrown by the FCM SDK is logged at warn level
     * and swallowed so that email delivery (the authoritative channel) is not disturbed.
     *
     * @param fcmToken device token to deliver to
     * @param notificationType order event type, e.g. {@code ORDER_CREATED} / {@code ORDER_DONE}
     * @param title user-visible notification title (rendered email subject)
     * @param body user-visible notification body (rendered email body)
     * @param variables order template variables (orderId, ticker, status, ...)
     */
    public void sendOrderPush(String fcmToken, String notificationType,
                              String title, String body, Map<String, String> variables) {
        if (!firebaseAvailable) {
            log.debug("Firebase not available, skipping order FCM push");
            return;
        }

        Map<String, String> data = variables == null ? Map.of() : variables;
        Message message = Message.builder()
                .setToken(fcmToken)
                .putData("type", notificationType)
                .putData("title", title != null ? title : "")
                .putData("body", body != null ? body : "")
                .putData("orderId", data.getOrDefault("orderId", ""))
                .putData("ticker", data.getOrDefault("ticker", ""))
                .putData("status", data.getOrDefault("status", ""))
                .setAndroidConfig(AndroidConfig.builder()
                        .setPriority(Priority.HIGH)
                        .build())
                .build();

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            log.info("FCM order push sent: messageId={}, type={}", messageId, notificationType);
        } catch (Exception e) {
            log.warn("FCM order push failed (email delivery is authoritative): {}", e.getMessage());
        }
    }
}
