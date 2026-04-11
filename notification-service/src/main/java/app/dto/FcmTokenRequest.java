package app.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for {@code PUT /notifications/fcm/token} — carries the mapping
 * between a Banka1 {@code clientId} and a Firebase Cloud Messaging device token
 * produced by the Firebase SDK on the mobile app.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FcmTokenRequest {

    /** Id of the client that owns the device. */
    @NotNull
    @Schema(description = "Client ID", example = "1")
    private Long clientId;

    /** Raw FCM device token produced by the Firebase SDK. */
    @NotBlank
    @Schema(description = "FCM device token", example = "dK3x...")
    private String fcmToken;
}
