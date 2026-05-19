package com.banka1.employeeService.audit;

/**
 * WP-12: RabbitMQ payload za audit dogadjaj — producer kopija za employee-service.
 *
 * <p>Polja moraju 1:1 odgovarati {@code AuditEventDto} koji konzumira
 * {@code AuditEventListener} u trading-service-u; Jackson serijalizuje/
 * deserijalizuje po imenima polja. {@code actionType} mora biti naziv jedne
 * {@code AuditActionType} konstante.
 *
 * @param actorId    ID aktora; {@code null} = SYSTEM aktor
 * @param actorName  citljivo ime aktora
 * @param actionType ime {@code AuditActionType} konstante (mora se poklapati)
 * @param targetType tip ciljnog entiteta
 * @param targetId   identifikator ciljnog entiteta kao string
 * @param details    slobodan opis (npr. stara/nova vrednost)
 * @param timestamp  trenutak dogadjaja u <b>epoch millis</b> (UTC)
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
