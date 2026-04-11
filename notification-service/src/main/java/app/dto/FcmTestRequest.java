package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for {@code POST /notifications/fcm/test} — used from Postman
 * and Swagger to send a single FCM push directly to a client's registered
 * device without going through verification-service or RabbitMQ.
 *
 * <p>This DTO deliberately mirrors the payload fields that
 * {@link app.service.FcmPushService#sendVerificationPush} sends so manual
 * tests exercise the same mobile-side rendering path as real verification
 * events.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FcmTestRequest {

    /** Target client whose registered device should receive the test push. */
    @NotNull
    @Schema(description = "Client ID to send test push to", example = "1")
    private Long clientId;

    /** 6-digit OTP that the mobile app will display in the notification. */
    @NotBlank
    @Schema(description = "6-digit code to send", example = "123456")
    private String code;

    /**
     * Business operation label shown on the device, typically one of
     * {@code PAYMENT} or {@code TRANSFER}. Optional — a fallback label is
     * substituted when null.
     */
    @Schema(description = "Operation type", example = "PAYMENT")
    private String operationType;

    /**
     * Verification session id passed through to the mobile app so it can call
     * {@code /verification/validate} after the user enters the code. Optional
     * for pure display smoke tests.
     */
    @Schema(description = "Session ID", example = "42")
    private String sessionId;
}
