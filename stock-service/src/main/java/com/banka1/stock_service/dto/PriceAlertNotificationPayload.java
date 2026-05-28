package com.banka1.stock_service.dto;

import java.util.Map;

/**
 * Plain Jackson-serializable RabbitMQ payload for a {@code price.alert_triggered}
 * notification (Celina 3.2).
 *
 * <p>The field layout maps onto the notification-service {@code NotificationRequest}:
 * <ul>
 *     <li>{@code username} — display name used in the email/in-app template</li>
 *     <li>{@code userEmail} — email destination; {@code null} when not resolvable
 *         from stock-service</li>
 *     <li>{@code templateVariables} — values for {@code PRICE_ALERT_TRIGGERED}
 *         template placeholders ({@code name}, {@code ticker}, {@code price},
 *         {@code threshold}, {@code condition})</li>
 *     <li>{@code clientId} — alert owner id when the owner is a client, enabling
 *         FCM push delivery; {@code null} for employee owners</li>
 * </ul>
 *
 * @param username display name used in template substitutions
 * @param userEmail destination email address, or {@code null} when unresolved
 * @param templateVariables template placeholder values
 * @param clientId alert owner id for push delivery, or {@code null} for employees
 */
public record PriceAlertNotificationPayload(
        String username,
        String userEmail,
        Map<String, String> templateVariables,
        Long clientId
) {
}
