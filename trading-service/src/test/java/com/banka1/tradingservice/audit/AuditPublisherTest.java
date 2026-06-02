package com.banka1.tradingservice.audit;

import com.banka1.tradingservice.audit.dto.AuditEventDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * WP-2: unit test za {@link AuditPublisher} — verifikuje izgradnju routing
 * key-a i robusnost na greske u slanju.
 */
@ExtendWith(MockitoExtension.class)
class AuditPublisherTest {

    private static final String EXCHANGE = "employee.events";

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void publishesWithLowercasedRoutingKey() {
        AuditPublisher publisher = new AuditPublisher(rabbitTemplate, EXCHANGE);
        AuditEventDto event = new AuditEventDto(
                7L, "Marko", "ORDER_APPROVED", "ORDER", "42", "d", 1L);

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("audit.order_approved"), eq(event));
    }

    @Test
    void usesUnknownSegmentForNullActionType() {
        AuditPublisher publisher = new AuditPublisher(rabbitTemplate, EXCHANGE);
        AuditEventDto event = new AuditEventDto(
                7L, "Marko", null, "ORDER", "42", "d", 1L);

        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("audit.unknown"), eq(event));
    }

    @Test
    void skipsNullEvent() {
        AuditPublisher publisher = new AuditPublisher(rabbitTemplate, EXCHANGE);

        publisher.publish(null);

        verify(rabbitTemplate, never())
                .convertAndSend(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(Object.class));
    }

    @Test
    void swallowsSendFailure() {
        AuditPublisher publisher = new AuditPublisher(rabbitTemplate, EXCHANGE);
        AuditEventDto event = new AuditEventDto(
                7L, "Marko", "TAX_RUN_MANUAL", "TAX", "1", "d", 1L);
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("audit.tax_run_manual"), eq(event));

        // ne sme da baci — audit je sporedan tok
        publisher.publish(event);

        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq("audit.tax_run_manual"), eq(event));
    }
}
