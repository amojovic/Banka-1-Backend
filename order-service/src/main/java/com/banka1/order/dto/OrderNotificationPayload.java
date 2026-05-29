package com.banka1.order.dto;

import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RabbitMQ payload for supervisor order decisions.
 *
 * <p>Fields mirror the notification-service NotificationRequest contract while
 * keeping order-specific metadata available in the same payload.</p>
 */
@Data
public class OrderNotificationPayload {
    private Long orderId;
    private OrderStatus status;
    private Long userId;
    /**
     * Client id used by notification-service for FCM token lookup. For client-placed orders this
     * equals {@link #userId}; agent/actuary orders set it too but simply have no registered device.
     */
    private Long clientId;
    private Long supervisorId;
    private Long listingId;
    private OrderType orderType;
    private OrderDirection direction;
    private String username;
    private String userEmail;
    private Map<String, String> templateVariables = new LinkedHashMap<>();
}
