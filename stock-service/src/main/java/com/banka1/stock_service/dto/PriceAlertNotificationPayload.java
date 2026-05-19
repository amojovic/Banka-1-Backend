package com.banka1.stock_service.dto;

import java.util.Map;

/**
 * Plain Jackson-serializable RabbitMQ payload for a {@code price.alert_triggered}
 * notification (Celina 3.2).
 *
 * <p>The field layout mirrors the notification-service {@code NotificationRequest}
 * contract so the message deserializes cleanly on the consumer side:
 *
 * <ul>
 *     <li>{@code username} — display name used in the email/in-app template</li>
 *     <li>{@code userEmail} — email destination; {@code null} when the alert owner's
 *         email cannot be resolved from stock-service, in which case only the
 *         in-app and push channels deliver</li>
 *     <li>{@code templateVariables} — values for the {@code PRICE_ALERT_TRIGGERED}
 *         template placeholders ({@code name}, {@code ticker}, {@code price},
 *         {@code threshold}, {@code condition})</li>
 *     <li>{@code recipientUserId} — alert owner id, used for the in-app feed row</li>
 *     <li>{@code recipientType} — {@code CLIENT} or {@code EMPLOYEE} id space</li>
 *     <li>{@code clientId} — alert owner id when the owner is a client, enabling
 *         push (FCM) delivery; {@code null} for employees</li>
 * </ul>
 *
 * @param username display name used in template substitutions
 * @param userEmail destination email address, or {@code null} when unresolved
 * @param templateVariables template placeholder values
 * @param recipientUserId alert owner id for the in-app feed
 * @param recipientType {@code CLIENT} or {@code EMPLOYEE}
 * @param clientId alert owner id for push delivery, or {@code null} for employees
 */
public record PriceAlertNotificationPayload(
        String username,
        String userEmail,
        Map<String, String> templateVariables,
        Long recipientUserId,
        String recipientType,
        Long clientId
) {
}
