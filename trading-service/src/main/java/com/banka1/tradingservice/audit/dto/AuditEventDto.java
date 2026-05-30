package com.banka1.tradingservice.audit.dto;

/**
 * WP-2: RabbitMQ payload za audit dogadjaj.
 *
 * <p>Producer servisi (WP-12+) publikuju ovaj zapis na topic exchange
 * ({@code employee.events}) sa routing key-em {@code audit.<actionTypeKey>}.
 * {@code AuditEventListener} u trading-service-u ga konzumira i mapira u
 * {@code AuditLog} red.
 *
 * <p>Plain Java record — Jackson serijalizuje/deserijalizuje po imenima polja.
 *
 * @param actorId    ID aktora; {@code null} = SYSTEM aktor
 * @param actorName  citljivo ime aktora
 * @param actionType ime {@code AuditActionType} konstante (mora se poklapati)
 * @param targetType tip ciljnog entiteta
 * @param targetId   identifikator ciljnog entiteta kao string
 * @param details    slobodan opis (npr. stara/nova vrednost)
 * @param timestamp  trenutak dogadjaja u <b>epoch millis</b> (UTC); ako je
 *                   {@code null}, listener koristi trenutak prijema poruke
 */
public record AuditEventDto(
        Long actorId,
        String actorName,
        String actionType,
        String targetType,
        String targetId,
        String details,
        Long timestamp
) {
}
