package com.banka1.tradingservice.audit;

import com.banka1.tradingservice.audit.dto.AuditEventDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * WP-2: lagani reusable publisher za audit dogadjaje.
 *
 * <p>{@link #publish(AuditEventDto)} salje DTO na deljeni topic exchange
 * ({@code ${rabbitmq.exchange}} = {@code NOTIFICATION_EXCHANGE}, default
 * {@code employee.events}) sa routing key-em {@code audit.<actionTypeKey>}.
 * {@code actionTypeKey} je lowercase-ovan naziv {@code AuditActionType}
 * konstante (npr. {@code ORDER_APPROVED} -> {@code audit.order_approved}).
 *
 * <p>{@code AuditEventListener} hvata sve {@code audit.#} poruke, tako da je
 * konkretan key informativan/za filtriranje — bilo koji validan key prolazi.
 *
 * <p>Ovo je kanonski referentni helper. U WP-12 svaki producer modul dobija
 * sopstvenu kopiju (svaki servis ima svoj package root); trading-service ga
 * koristi direktno iz ovog paketa.
 */
@Slf4j
@Component
public class AuditPublisher {

    /** Routing-key prefiks za sve audit dogadjaje; mora se poklapati sa {@code audit.#} binding-om. */
    private static final String ROUTING_KEY_PREFIX = "audit.";

    private final RabbitTemplate rabbitTemplate;
    private final String exchange;

    public AuditPublisher(RabbitTemplate rabbitTemplate,
                          @Value("${rabbitmq.exchange}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
    }

    /**
     * Publikuje audit dogadjaj. Greske u slanju se loguju i progutaju — audit
     * je sporedan tok i ne sme da obori biznis transakciju.
     *
     * @param event audit dogadjaj; {@code actionType} treba da odgovara
     *              {@code AuditActionType} konstanti
     */
    public void publish(AuditEventDto event) {
        if (event == null) {
            log.warn("audit publish: null event — preskacem");
            return;
        }
        String routingKey = ROUTING_KEY_PREFIX + routingKeySegment(event.actionType());
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event);
            log.debug("audit publish: exchange={} key={} actor={} target={}/{}",
                    exchange, routingKey, event.actorId(), event.targetType(), event.targetId());
        } catch (Exception ex) {
            log.error("audit publish: slanje nije uspelo (key={}): {}", routingKey, ex.toString(), ex);
        }
    }

    private String routingKeySegment(String actionType) {
        if (actionType == null || actionType.isBlank()) {
            return "unknown";
        }
        return actionType.trim().toLowerCase();
    }
}
