package com.banka1.order.rabbitmq;

import com.banka1.order.dto.OrderNotificationPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.verify;

/**
 * Verifies that {@link OrderNotificationProducer} publishes each order-lifecycle
 * event to the configured exchange under the routing key the notification-service
 * binds with its {@code order.#} pattern.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationProducerTest {

    private static final String EXCHANGE = "employee.events";

    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private OrderNotificationProducer producer;

    private OrderNotificationPayload payload;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(producer, "exchange", EXCHANGE);
        payload = new OrderNotificationPayload();
    }

    @Test
    void sendOrderApproved_publishesUnderApprovedRoutingKey() {
        producer.sendOrderApproved(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.approved", payload);
    }

    @Test
    void sendOrderDeclined_publishesUnderDeclinedRoutingKey() {
        producer.sendOrderDeclined(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.declined", payload);
    }

    @Test
    void sendTaxCollected_publishesUnderTaxCollectedRoutingKey() {
        producer.sendTaxCollected(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "tax.collected", payload);
    }

    @Test
    void sendOrderCreated_publishesUnderCreatedRoutingKey() {
        producer.sendOrderCreated(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.created", payload);
    }

    @Test
    void sendOrderExecuted_publishesUnderExecutedRoutingKey() {
        producer.sendOrderExecuted(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.executed", payload);
    }

    @Test
    void sendOrderPartialFill_publishesUnderPartialFillRoutingKey() {
        producer.sendOrderPartialFill(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.partial_fill", payload);
    }

    @Test
    void sendOrderCancelled_publishesUnderCancelledRoutingKey() {
        producer.sendOrderCancelled(payload);

        verify(rabbitTemplate).convertAndSend(EXCHANGE, "order.cancelled", payload);
    }
}
