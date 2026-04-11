package app.controller;

import app.dto.FcmTestRequest;
import app.dto.FcmTokenRequest;
import app.service.FcmPushService;
import app.service.FcmTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * REST surface for managing Firebase Cloud Messaging device tokens and for
 * manually triggering a push during development and integration testing.
 *
 * <p>All endpoints live under {@code /notifications/fcm} and are proxied
 * through nginx by the api-gateway. The register/deregister endpoints are
 * called by the mobile app on login and logout; the {@code /test} endpoint is
 * used from Postman or Swagger UI to verify the FCM wiring end-to-end without
 * having to go through verification-service and RabbitMQ.
 */
@RestController
@RequestMapping("/notifications/fcm")
@RequiredArgsConstructor
@Tag(name = "FCM Token Management", description = "Register and test Firebase Cloud Messaging tokens")
public class FcmTokenController {

    /** Application service that manages the {@code fcm_tokens} registry. */
    private final FcmTokenService fcmTokenService;
    /** Application service that performs the actual FCM send. */
    private final FcmPushService fcmPushService;

    /**
     * Registers a new FCM token for a client or refreshes the existing one.
     *
     * <p>This is the endpoint the mobile app calls after login as soon as the
     * Firebase SDK has produced a device token. Re-invocation is safe — the
     * token is upserted in place.
     *
     * @param request body with {@code clientId} and the raw {@code fcmToken}
     * @return HTTP 200 on successful persistence
     */
    @PutMapping("/token")
    @Operation(summary = "Register or update FCM token for a client")
    public ResponseEntity<Void> registerToken(@Valid @RequestBody FcmTokenRequest request) {
        fcmTokenService.upsertToken(request.getClientId(), request.getFcmToken());
        return ResponseEntity.ok().build();
    }

    /**
     * Removes the FCM token registration for a client.
     *
     * <p>Called by the mobile app on logout so the service stops targeting a
     * device that should no longer receive verification pushes.
     *
     * @param clientId id of the client whose device should be deregistered
     * @return HTTP 200 regardless of whether a row existed
     */
    @DeleteMapping("/token/{clientId}")
    @Operation(summary = "Deregister FCM token for a client (called on logout)")
    public ResponseEntity<Void> deregisterToken(@PathVariable Long clientId) {
        fcmTokenService.deleteToken(clientId);
        return ResponseEntity.ok().build();
    }

    /**
     * Sends a single test FCM push directly to the client's registered device.
     *
     * <p>This endpoint bypasses verification-service and RabbitMQ entirely. It
     * looks up the currently registered token for {@code clientId} and sends a
     * push with whatever {@code code}, {@code operationType}, and
     * {@code sessionId} the caller supplies. It exists purely for manual
     * testing from Postman/Swagger while iterating on the mobile display code.
     *
     * @param request body with the target {@code clientId}, the code to
     *                display, and optional operation/session metadata
     * @return HTTP 200 with a confirmation message on success, or HTTP 400
     *         when no token is registered for the given client
     */
    @PostMapping("/test")
    @Operation(summary = "Send a test FCM push notification (for Swagger testing)")
    public ResponseEntity<String> testPush(@Valid @RequestBody FcmTestRequest request) {
        Optional<String> token = fcmTokenService.findToken(request.getClientId());
        if (token.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body("No FCM token registered for clientId=" + request.getClientId());
        }

        fcmPushService.sendVerificationPush(
                token.get(),
                request.getCode(),
                request.getOperationType(),
                request.getSessionId()
        );
        return ResponseEntity.ok("FCM push sent to clientId=" + request.getClientId());
    }
}
