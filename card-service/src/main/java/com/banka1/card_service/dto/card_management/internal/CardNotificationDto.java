package com.banka1.card_service.dto.card_management.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * RabbitMQ payload compatible with notification-service NotificationRequest contract.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CardNotificationDto {

    private String username;
    private String userEmail;
    private Map<String, String> templateVariables;

    /**
     * Identifier of the user the in-app notification belongs to. Mapped onto
     * {@code recipientUserId} on the consumer; when {@code null} the consumer
     * skips the in-app row and only the email is delivered.
     */
    private Long recipientUserId;

    /**
     * Recipient id-space discriminator — {@code CLIENT} or {@code EMPLOYEE}.
     * Card lifecycle notifications always target the card holder ({@code CLIENT}).
     * Mapped onto {@code recipientType} on the consumer.
     */
    private String recipientType;

    /**
     * Backwards-compatible constructor for payloads that carry no in-app
     * recipient identity.
     *
     * @param username display username
     * @param userEmail recipient email
     * @param templateVariables template substitution values
     */
    public CardNotificationDto(String username, String userEmail, Map<String, String> templateVariables) {
        this.username = username;
        this.userEmail = userEmail;
        this.templateVariables = templateVariables;
    }
}
