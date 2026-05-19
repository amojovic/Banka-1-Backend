package com.banka1.order.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ payload published when a standing (recurring) order is skipped — Celina 3.6.
 *
 * <p>The field names mirror the notification-service {@code NotificationRequest}
 * contract so the consumer can render the {@code RECURRING_ORDER_SKIPPED} template
 * and create the owner's in-app feed row. The notification type is resolved by the
 * consumer from the RabbitMQ routing key, not from this payload.
 *
 * <p>{@code templateVariables} carries the {@code name}, {@code orderId} and
 * {@code reason} placeholders the {@code RECURRING_ORDER_SKIPPED} template expects.
 */
@Data
public class RecurringOrderSkippedNotification {

    /** Human-readable name used by templates; the owner's display name when known. */
    private String username;

    /** Owner's email address for the email-delivery leg; null skips only that leg. */
    private String userEmail;

    /** Identifier of the standing order's owner — drives the in-app notification feed row. */
    private Long recipientUserId;

    /** Recipient id-space discriminator — {@code CLIENT} or {@code EMPLOYEE}. */
    private String recipientType;

    /** Template placeholders: {@code name}, {@code orderId}, {@code reason}. */
    private Map<String, String> templateVariables = new LinkedHashMap<>();
}
