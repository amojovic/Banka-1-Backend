package com.banka1.tradingservice.audit.dto;

import com.banka1.tradingservice.audit.domain.AuditLog;

import java.time.LocalDateTime;

/**
 * WP-2: read-model za audit log redove ({@code GET /audit}).
 *
 * @param id         primarni kljuc audit reda
 * @param actorId    ID aktora; {@code null} = SYSTEM aktor
 * @param actorName  citljivo ime aktora
 * @param actionType ime {@code AuditActionType} konstante
 * @param targetType tip ciljnog entiteta
 * @param targetId   identifikator ciljnog entiteta
 * @param details    slobodan opis
 * @param createdAt  trenutak kreiranja audit reda
 */
public record AuditLogDto(
        Long id,
        Long actorId,
        String actorName,
        String actionType,
        String targetType,
        String targetId,
        String details,
        LocalDateTime createdAt
) {

    /** Mapira JPA entitet u read-model DTO. */
    public static AuditLogDto from(AuditLog entity) {
        return new AuditLogDto(
                entity.getId(),
                entity.getActorId(),
                entity.getActorName(),
                entity.getActionType() != null ? entity.getActionType().name() : null,
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getDetails(),
                entity.getCreatedAt()
        );
    }
}
