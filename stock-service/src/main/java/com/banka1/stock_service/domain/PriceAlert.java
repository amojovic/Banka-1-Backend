package com.banka1.stock_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JPA entity for a user-defined price alert (Celina 3.2), stored in the
 * {@code price_alerts} table.
 */
@Entity
@Table(name = "price_alerts")
@Getter
@Setter
public class PriceAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "recipient_type", nullable = false, length = 16)
    private String recipientType;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition", nullable = false, length = 32)
    private PriceAlertCondition condition;

    @Column(name = "threshold", nullable = false, precision = 19, scale = 4)
    private BigDecimal threshold;

    @Column(name = "notification_type", nullable = false, length = 16)
    private String notificationType;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;
}
