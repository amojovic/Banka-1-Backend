package com.banka1.order.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ payload for the ORDER_RECURRING_SKIPPED notification.
 *
 * <p>Mirrors the notification-service {@code NotificationRequest} contract.
 * {@code clientId} drives FCM token lookup — set to the owner's user id for client
 * owners so the push fires; null for actuary/agent owners (email remains authoritative).
 */
@Data
public class RecurringOrderSkippedNotification {

    private String username;
    private String userEmail;

    /**
     * Client id used by notification-service for FCM token lookup.
     * Set to the standing-order owner's user id for client owners; null for actuaries.
     */
    private Long clientId;

    private Map<String, String> templateVariables = new LinkedHashMap<>();
}
