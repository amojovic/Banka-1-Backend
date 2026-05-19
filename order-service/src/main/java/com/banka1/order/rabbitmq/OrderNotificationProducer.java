package com.banka1.order.rabbitmq;

import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ producer for order and tax notifications.
 * Publishes messages to the {@code employee.events} topic exchange
 * using the routing keys defined in the Celina 3 specification.
 */
@Component
@RequiredArgsConstructor
public class OrderNotificationProducer {

    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    /**
     * Publishes a notification that an order has been approved and executed.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderApproved(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.approved", payload);
    }

    /**
     * Publishes a notification that an order has been declined.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderDeclined(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.declined", payload);
    }

    /**
     * Publishes a notification that tax has been collected from a portfolio transaction.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendTaxCollected(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "tax.collected", payload);
    }

    /**
     * Publishes a notification that an order has been created and submitted for
     * confirmation/approval.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderCreated(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.created", payload);
    }

    /**
     * Publishes a notification that an order has been fully executed
     * ({@code isDone=true} — every portion filled).
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderExecuted(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.executed", payload);
    }

    /**
     * Publishes a notification that an order has been partially filled — one
     * portion executed while units remain outstanding.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderPartialFill(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.partial_fill", payload);
    }

    /**
     * Publishes a notification that an order has been cancelled before full
     * execution (manual cancellation or an automatic settlement-date expiry).
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendOrderCancelled(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.cancelled", payload);
    }

    /**
     * Publishes a notification that a standing (recurring) order run was skipped —
     * Celina 3.6, typically because the owner's account had insufficient funds, the
     * same way a missed loan installment is notified.
     *
     * <p>Published under the {@code order.recurring_skipped} routing key so it is
     * delivered through the notification-service's existing {@code order.#} queue
     * binding. The {@code recurring.order_skipped} routing key the WP-1 properties
     * already map to {@code RECURRING_ORDER_SKIPPED} has no queue binding, so it
     * cannot be used directly without an infrastructure change.
     *
     * @param payload the notification payload (serialized to JSON by Jackson)
     */
    public void sendRecurringOrderSkipped(Object payload) {
        rabbitTemplate.convertAndSend(exchange, "order.recurring_skipped", payload);
    }
}
