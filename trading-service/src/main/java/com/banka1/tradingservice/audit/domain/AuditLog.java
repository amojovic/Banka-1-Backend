package com.banka1.tradingservice.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * WP-2: red u centralizovanom audit log-u (tabela {@code audit_log}).
 *
 * <p>Jedan red = jedna auditovana akcija. Persistuje ga {@code AuditEventListener}
 * iz RabbitMQ {@code audit.#} poruke. Tabelu definise Liquibase changeset
 * {@code trading-otc/011-audit-log.sql}; mapiranje mora tacno odgovarati semi
 * jer trading-service radi sa {@code spring.jpa.hibernate.ddl-auto=validate}.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID aktora koji je izvrsio akciju; {@code null} = SYSTEM aktor. */
    @Column(name = "actor_id")
    private Long actorId;

    /** Citljivo ime aktora (npr. ime zaposlenog ili "SYSTEM"). */
    @Column(name = "actor_name", length = 255)
    private String actorName;

    /** Tip akcije; cuva se kao {@link Enum#name()} string. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, length = 64)
    private AuditActionType actionType;

    /** Tip ciljnog entiteta (npr. {@code ORDER}, {@code EMPLOYEE}). */
    @Column(name = "target_type", length = 64)
    private String targetType;

    /** Identifikator ciljnog entiteta, kao string. */
    @Column(name = "target_id", length = 64)
    private String targetId;

    /** Slobodan opis (npr. stara/nova vrednost). */
    @Column(name = "details", columnDefinition = "TEXT")
    private String details;

    /** Trenutak kreiranja audit reda. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
