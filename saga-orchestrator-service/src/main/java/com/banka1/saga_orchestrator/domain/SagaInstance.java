package com.banka1.saga_orchestrator.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Persistentno stanje jedne SAGA instance. Polja po DoD-u Issue #214.
 * payload i compensationLog su JSONB kolone čuvane kao Jackson stabla
 * (Map ili List sa proizvoljnim sadržajem) — orchestrator ih tipuje na
 * runtime kroz {@code ObjectMapper.convertValue}.
 */
@Entity
@Table(name = "saga_instance")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SagaInstance {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "saga_type", nullable = false, length = 64)
    private SagaType sagaType;

    @Column(name = "current_step", nullable = false)
    private int currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SagaState state;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Object payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "compensation_log", columnDefinition = "jsonb")
    private Object compensationLog;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (state == null) {
            state = SagaState.STARTED;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
