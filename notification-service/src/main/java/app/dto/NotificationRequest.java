package app.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Message payload consumed from RabbitMQ.
 *
 * <p>Contract used by this service:
 * <ul>
 *     <li>{@code username} is optional and can be used by templates</li>
 *     <li>{@code userEmail} is required for email delivery</li>
 *     <li>{@code templateVariables} carries template values
 *     including optional {@code subject}/{@code body}</li>
 *     <li>notification type is resolved from RabbitMQ routing key, not from payload</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
public class NotificationRequest implements Serializable {
    /**
     * Human-readable username used in template substitutions.
     */
    @Schema(description = "User display name", example = "Mila")
    private String username;

    /**
     * Destination email address for the notification.
     */
    @JsonAlias({"email", "recipientEmail"})
    @Schema(description = "Target email address", example = "employee@example.com")
    private String userEmail;

    /**
     * Dynamic template values; may include {@code subject},
     * {@code body}, or domain-specific placeholders.
     */
    @JsonAlias({"params", "data", "payload", "userData"})
    @Schema(description = "Template and message variables")
    private Map<String, String> templateVariables = new HashMap<>();

    /**
     * Client ID for FCM token lookup (nullable, backward-compatible).
     */
    @Schema(description = "Client ID for push notification delivery")
    private Long clientId;

    /**
     * Operation type (e.g. PAYMENT, TRANSFER) for display in push notification.
     */
    @Schema(description = "Operation type for the verification")
    private String operationType;

    /**
     * Verification session ID, passed to mobile for validate calls.
     */
    @Schema(description = "Verification session ID")
    private String sessionId;

    /**
     * Identifier of the user the in-app notification belongs to (nullable,
     * backward-compatible). When null the consumer skips the in-app row and
     * email delivery remains the only channel.
     */
    @Schema(description = "Recipient user id for the in-app notification feed")
    private Long recipientUserId;

    /**
     * Recipient id space discriminator — {@code CLIENT} or {@code EMPLOYEE}
     * (nullable, backward-compatible). Required together with
     * {@code recipientUserId} for an in-app row to be created.
     */
    @Schema(description = "Recipient type: CLIENT or EMPLOYEE", example = "CLIENT")
    private String recipientType;

    /**
     * Convenience constructor for tests and manual object creation.
     *
     * @param reqName          display username
     * @param reqEmail         recipient email
     * @param templateVar key-value template placeholders
     */
    public NotificationRequest(String reqName, String reqEmail, Map<String, String> templateVar) {
        this.username = reqName;
        this.userEmail = reqEmail;
        if (templateVar != null) {
            this.templateVariables = new HashMap<>(templateVar);
        }
    }
}
