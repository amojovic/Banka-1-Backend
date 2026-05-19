package com.banka1.transaction_service.rabbitMQ;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

/**
 * DTO sent to the RabbitMQ email service.
 * Contains all necessary data for generating and sending email notifications.
 * Fields with {@code null} values are excluded from JSON serialization.
 *
 * <p>The notification-service consumer renders the email body by substituting
 * {@code {{key}}} placeholders from {@code templateVariables} (and the alias
 * {@code username} -> {@code {{name}}}). The {@code TRANSACTION_COMPLETED}
 * template renders {@code {{amount}}}; {@code TRANSACTION_DENIED} additionally
 * renders {@code {{reason}}}. Those values must therefore be placed into
 * {@code templateVariables} by the publishing code.
 */
@NoArgsConstructor
@Getter
@Setter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailDto {

    /**
     * Email address of the notification recipient.
     */
    private String userEmail;

    /**
     * Name or username of the recipient (used in the email text).
     */
    private String username;

    /**
     * Type of email notification that determines the content and template of the email.
     */
    private EmailType emailType;

    /**
     * Template variables forwarded to the notification-service template engine.
     * Mapped onto {@code templateVariables} of the consumer-side payload. Carries
     * {@code amount} for both transaction types and {@code reason} for
     * {@code TRANSACTION_DENIED}.
     */
    private Map<String, String> templateVariables = new HashMap<>();

    /**
     * Identifier of the user the in-app notification belongs to. Mapped onto
     * {@code recipientUserId} on the consumer; when {@code null} the consumer
     * skips the in-app row and only the email is delivered.
     */
    private Long recipientUserId;

    /**
     * Recipient id-space discriminator — {@code CLIENT} or {@code EMPLOYEE}.
     * Payment notifications always target the sending client ({@code CLIENT}).
     * Mapped onto {@code recipientType} on the consumer.
     */
    private String recipientType;

    /**
     * Creates a payload for an email intended to notify the user about a transaction.
     *
     * @param username the username or display name for the email
     * @param userEmail the email address of the recipient
     * @param emailType the type of notification (TRANSACTION_COMPLETED or TRANSACTION_DENIED)
     */
    public EmailDto(String username, String userEmail, EmailType emailType) {
        this.userEmail = userEmail;
        this.username = username;
        this.emailType = emailType;
        this.templateVariables = new HashMap<>();
    }
}
