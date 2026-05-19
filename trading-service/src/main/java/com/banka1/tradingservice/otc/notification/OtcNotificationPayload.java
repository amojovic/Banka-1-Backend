package com.banka1.tradingservice.otc.notification;

import java.util.Map;

/**
 * WP-15 (Celina 4.1): plain Jackson-serializable RabbitMQ payload for an OTC
 * negotiation notification (counter-offer, accepted, rejected, withdrawn, or
 * option-contract expiring soon).
 *
 * <p>The field layout mirrors the notification-service {@code NotificationRequest}
 * contract so the message deserializes cleanly on the consumer side:
 *
 * <ul>
 *     <li>{@code username} — display name used in the email/in-app template; the
 *         notification-service content resolver aliases it to the {@code {{name}}}
 *         placeholder</li>
 *     <li>{@code userEmail} — email destination; {@code null} when the
 *         counterparty's email cannot be resolved, in which case only the in-app
 *         channel delivers</li>
 *     <li>{@code templateVariables} — values for the OTC template placeholders
 *         ({@code negotiationId} for counter-offer/accepted/rejected/withdrawn;
 *         {@code contractId} + {@code expiryDate} for contract-expiring)</li>
 *     <li>{@code recipientUserId} — counterparty id, used for the in-app feed row</li>
 *     <li>{@code recipientType} — {@code CLIENT} or {@code EMPLOYEE} id space</li>
 *     <li>{@code clientId} — counterparty id when the counterparty is a client,
 *         enabling push (FCM) delivery; {@code null} for employees</li>
 * </ul>
 *
 * <p>The notification type is resolved from the RabbitMQ routing key, not from
 * this payload.
 *
 * @param username display name used in template substitutions
 * @param userEmail destination email address, or {@code null} when unresolved
 * @param templateVariables template placeholder values
 * @param recipientUserId counterparty id for the in-app feed
 * @param recipientType {@code CLIENT} or {@code EMPLOYEE}
 * @param clientId counterparty id for push delivery, or {@code null} for employees
 */
public record OtcNotificationPayload(
        String username,
        String userEmail,
        Map<String, String> templateVariables,
        Long recipientUserId,
        String recipientType,
        Long clientId
) {
}
