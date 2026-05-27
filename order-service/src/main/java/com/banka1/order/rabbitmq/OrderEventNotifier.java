package com.banka1.order.rabbitmq;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.OrderNotificationPayload;
import com.banka1.order.entity.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes order lifecycle notifications (created, fully executed, partial fill, auto-cancelled)
 * to RabbitMQ for notification-service to deliver via email + FCM push.
 *
 * <p>Recipient resolution: client orders are resolved through {@link ClientClient} (so the email
 * renders and the FCM push — keyed on {@code clientId == order.userId} — reaches the mobile device);
 * agent/actuary orders fall back to {@link EmployeeClient}. {@code clientId} is always set to the
 * order owner; agent orders simply have no registered device, so the push is skipped downstream.
 *
 * <p>All publishing is best-effort: any failure resolving the recipient or sending is logged and
 * swallowed so it never blocks order creation/execution.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderEventNotifier {

    private final OrderNotificationProducer orderNotificationProducer;
    private final ClientClient clientClient;
    private final EmployeeClient employeeClient;

    /** Order lifecycle events that produce a client-facing notification. */
    public enum OrderEventType { CREATED, DONE, PARTIAL_FILL, AUTO_CANCELLED }

    /**
     * Builds and publishes a lifecycle notification for the given order.
     *
     * @param order the order the event concerns
     * @param eventType the lifecycle event
     * @param ticker the security ticker if known (may be null)
     * @param price the relevant price per unit (execution price or reference price; may be null)
     */
    public void notifyOrderEvent(Order order, OrderEventType eventType, String ticker, BigDecimal price) {
        Runnable publish = () -> doPublish(order, eventType, ticker, price);

        // The publish must happen strictly after the surrounding DB transaction commits. The
        // RabbitTemplate is not channel-transacted, so publishing inline would send the message
        // immediately — and if the commit later fails, the execution attempt is retried and the
        // same notification is republished, producing duplicate FCM pushes on the mobile client.
        // Deferring to afterCommit guarantees exactly one publish per durably-persisted event.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            // No active transaction (e.g. invoked outside a @Transactional context) — publish directly.
            publish.run();
        }
    }

    private void doPublish(Order order, OrderEventType eventType, String ticker, BigDecimal price) {
        try {
            Recipient recipient = resolveRecipient(order.getUserId());
            OrderNotificationPayload payload = buildPayload(order, eventType, ticker, price, recipient);
            switch (eventType) {
                case CREATED -> orderNotificationProducer.sendOrderCreated(payload);
                case DONE -> orderNotificationProducer.sendOrderDone(payload);
                case PARTIAL_FILL -> orderNotificationProducer.sendOrderPartialFill(payload);
                case AUTO_CANCELLED -> orderNotificationProducer.sendOrderAutoCancelled(payload);
                default -> { }
            }
        } catch (Exception ex) {
            log.warn("Failed to publish order {} notification for event {}: {}",
                    order.getId(), eventType, ex.toString());
        }
    }

    private OrderNotificationPayload buildPayload(Order order, OrderEventType eventType, String ticker,
                                                  BigDecimal price, Recipient recipient) {
        OrderNotificationPayload payload = new OrderNotificationPayload();
        payload.setOrderId(order.getId());
        payload.setStatus(order.getStatus());
        payload.setUserId(order.getUserId());
        payload.setClientId(order.getUserId());
        payload.setListingId(order.getListingId());
        payload.setOrderType(order.getOrderType());
        payload.setDirection(order.getDirection());
        payload.setUsername(recipient.name());
        payload.setUserEmail(recipient.email());

        Map<String, String> variables = new LinkedHashMap<>();
        if (recipient.name() != null) {
            variables.put("name", recipient.name());
        }
        variables.put("orderId", String.valueOf(order.getId()));
        variables.put("status", order.getStatus() == null ? "" : order.getStatus().name());
        variables.put("ticker", ticker == null ? "" : ticker);
        variables.put("listingId", String.valueOf(order.getListingId()));
        variables.put("quantity", String.valueOf(order.getQuantity()));
        variables.put("remainingPortions", String.valueOf(order.getRemainingPortions()));
        variables.put("price", price == null ? "" : price.toPlainString());
        variables.put("orderType", order.getOrderType() == null ? "" : order.getOrderType().name());
        variables.put("direction", order.getDirection() == null ? "" : order.getDirection().name());
        variables.put("event", eventType.name());
        payload.setTemplateVariables(variables);
        return payload;
    }

    private Recipient resolveRecipient(Long userId) {
        // Clients are the push audience — resolve them first so email renders and FCM reaches the device.
        try {
            CustomerDto customer = clientClient.getCustomer(userId);
            if (customer != null && customer.getEmail() != null && !customer.getEmail().isBlank()) {
                return new Recipient(formatCustomerName(customer), customer.getEmail());
            }
        } catch (RuntimeException ex) {
            log.debug("Order owner {} is not a client (or lookup failed): {}", userId, ex.toString());
        }
        try {
            EmployeeDto employee = employeeClient.getEmployee(userId);
            if (employee != null) {
                return new Recipient(formatEmployeeName(employee), employee.getEmail());
            }
        } catch (RuntimeException ex) {
            log.debug("Order owner {} is not an employee (or lookup failed): {}", userId, ex.toString());
        }
        return new Recipient(null, null);
    }

    private String formatCustomerName(CustomerDto customer) {
        String first = customer.getFirstName() == null ? "" : customer.getFirstName().trim();
        String last = customer.getLastName() == null ? "" : customer.getLastName().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? null : full;
    }

    private String formatEmployeeName(EmployeeDto employee) {
        String first = employee.getIme() == null ? "" : employee.getIme().trim();
        String last = employee.getPrezime() == null ? "" : employee.getPrezime().trim();
        String full = (first + " " + last).trim();
        return full.isEmpty() ? employee.getUsername() : full;
    }

    private record Recipient(String name, String email) {
    }
}
