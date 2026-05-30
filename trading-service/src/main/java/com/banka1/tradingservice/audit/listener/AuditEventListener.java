package com.banka1.tradingservice.audit.listener;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import com.banka1.tradingservice.audit.dto.AuditEventDto;
import com.banka1.tradingservice.audit.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * WP-2: konzumira {@code audit.#} dogadjaje sa deljenog topic exchange-a i
 * persistuje po jedan {@link AuditLog} red.
 *
 * <p>Exchange je {@code ${rabbitmq.exchange}} (= {@code NOTIFICATION_EXCHANGE},
 * default {@code employee.events}) — isti exchange na koji producer servisi
 * publikuju audit dogadjaje. Queue {@code audit-log-queue} je durable i vezan
 * routing pattern-om {@code audit.#}.
 *
 * <p>Robusnost: ako {@code actionType} ne odgovara nijednoj {@link AuditActionType}
 * konstanti, poruka se loguje kao upozorenje i preskace (listener ne puca, a
 * poruka se ne re-queue-uje cime bi se napravila beskonacna petlja).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventListener {

    private final AuditLogRepository auditLogRepository;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(value = "audit-log-queue", durable = "true"),
            exchange = @Exchange(value = "${rabbitmq.exchange}", type = ExchangeTypes.TOPIC),
            key = "audit.#"
    ))
    @Transactional
    public void onAuditEvent(AuditEventDto event) {
        if (event == null) {
            log.warn("audit: primljen null payload — preskacem");
            return;
        }

        AuditActionType actionType = parseActionType(event.actionType());
        if (actionType == null) {
            log.warn("audit: nepoznat actionType '{}' — poruka preskocena (actor={}, target={}/{})",
                    event.actionType(), event.actorId(), event.targetType(), event.targetId());
            return;
        }

        AuditLog entry = AuditLog.builder()
                .actorId(event.actorId())
                .actorName(event.actorName())
                .actionType(actionType)
                .targetType(event.targetType())
                .targetId(event.targetId())
                .details(event.details())
                .createdAt(resolveTimestamp(event.timestamp()))
                .build();

        auditLogRepository.save(entry);
        log.debug("audit: upisan red actionType={} actor={} target={}/{}",
                actionType, event.actorId(), event.targetType(), event.targetId());
    }

    private AuditActionType parseActionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditActionType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Epoch millis (UTC) -> lokalni {@link LocalDateTime}; null -> trenutak prijema. */
    private LocalDateTime resolveTimestamp(Long epochMillis) {
        if (epochMillis == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
