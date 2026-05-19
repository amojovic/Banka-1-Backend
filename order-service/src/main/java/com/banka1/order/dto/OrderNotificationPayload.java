package com.banka1.order.dto;

import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import lombok.Data;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ payload for order-lifecycle notifications (created, approved, declined,
 * executed, partial fill, cancelled).
 *
 * <p>Fields mirror the notification-service {@code NotificationRequest} contract
 * while keeping order-specific metadata available in the same payload. The
 * notification type is resolved from the RabbitMQ routing key, not from this
 * payload.</p>
 *
 * <p>{@code recipientUserId} + {@code recipientType} are matched by JSON field
 * name on {@code NotificationRequest}; together they let the consumer create the
 * in-app notification feed row. {@code userEmail} drives email delivery.</p>
 */
@Data
public class OrderNotificationPayload {
    private Long orderId;
    private OrderStatus status;
    private Long userId;
    private Long supervisorId;
    private Long listingId;
    private OrderType orderType;
    private OrderDirection direction;
    private String username;
    private String userEmail;

    /** Number of units the order was placed for. */
    private Integer quantity;

    /** Number of units executed by the event being notified (partial-fill leg). */
    private Integer filledQuantity;

    /** Brokerage fee/commission applicable to the order, in the security's currency. */
    private BigDecimal fee;

    /**
     * Identifier of the order owner the in-app notification belongs to. Matched
     * by name against {@code NotificationRequest.recipientUserId}.
     */
    private Long recipientUserId;

    /**
     * Recipient id-space discriminator — {@code CLIENT} or {@code EMPLOYEE}.
     * Matched by name against {@code NotificationRequest.recipientType}.
     */
    private String recipientType;

    private Map<String, String> templateVariables = new LinkedHashMap<>();
}
