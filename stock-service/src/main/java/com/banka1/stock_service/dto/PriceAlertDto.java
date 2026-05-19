package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.PriceAlert;
import com.banka1.stock_service.domain.PriceAlertCondition;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API response view of a {@link PriceAlert} (Celina 3.2).
 *
 * @param id technical identifier of the alert
 * @param userId owner identifier
 * @param recipientType owner identity space — {@code CLIENT} or {@code EMPLOYEE}
 * @param listingId tracked listing identifier
 * @param condition trigger condition
 * @param threshold numeric threshold compared against the listing price
 * @param notificationType preferred delivery channel
 * @param active whether the alert is currently evaluated
 * @param createdAt creation timestamp
 * @param lastTriggeredAt timestamp of the last fire, or {@code null} if never fired
 */
public record PriceAlertDto(
        Long id,
        Long userId,
        String recipientType,
        Long listingId,
        PriceAlertCondition condition,
        BigDecimal threshold,
        String notificationType,
        boolean active,
        LocalDateTime createdAt,
        LocalDateTime lastTriggeredAt
) {

    /**
     * Maps a persisted {@link PriceAlert} entity to its API response view.
     *
     * @param alert persisted entity
     * @return response DTO
     */
    public static PriceAlertDto from(PriceAlert alert) {
        return new PriceAlertDto(
                alert.getId(),
                alert.getUserId(),
                alert.getRecipientType(),
                alert.getListingId(),
                alert.getCondition(),
                alert.getThreshold(),
                alert.getNotificationType(),
                alert.isActive(),
                alert.getCreatedAt(),
                alert.getLastTriggeredAt()
        );
    }
}
