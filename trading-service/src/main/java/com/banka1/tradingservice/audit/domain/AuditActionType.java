package com.banka1.tradingservice.audit.domain;

/**
 * WP-2: tipovi auditovanih akcija u centralizovanom audit log-u.
 *
 * <p>Vrednost se cuva u koloni {@code audit_log.action_type} kao {@link Enum#name()}
 * string. Producer servisi (WP-12+) salju ime jedne od ovih konstanti u
 * {@code AuditEventDto.actionType}; ako string ne odgovara nijednoj konstanti
 * {@code AuditEventListener} odbacuje poruku uz upozorenje (ne pada).
 */
public enum AuditActionType {

    /** Zaposleni/supervizor je odobrio order. */
    ORDER_APPROVED,

    /** Zaposleni/supervizor je odbio order. */
    ORDER_DECLINED,

    /** Promenjen je trgovacki limit agenta (aktuara). */
    AGENT_LIMIT_CHANGED,

    /** Iskorisceni limit agenta je resetovan. */
    AGENT_USED_LIMIT_RESET,

    /** Promenjen je flag "zahteva odobrenje" za agenta. */
    AGENT_NEED_APPROVAL_CHANGED,

    /** Promenjene su permisije zaposlenog. */
    EMPLOYEE_PERMISSIONS_CHANGED,

    /** Manuelno pokrenut obracun poreza. */
    TAX_RUN_MANUAL,

    /** Zakazani (cron) obracun poreza. */
    TAX_RUN_SCHEDULED
}
