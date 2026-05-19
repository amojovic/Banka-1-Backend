package com.banka1.order.rabbitmq;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.OrderNotificationPayload;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.enums.OrderStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds {@link OrderNotificationPayload} instances for order-lifecycle events.
 *
 * <p>An order's owner ({@link Order#getUserId()}) can be either a bank client
 * (role {@code CLIENT_TRADING}) or an actuary/agent (an employee). This factory
 * resolves the owner's email and {@code recipientType} so the notification-service
 * can both deliver an email and create the in-app feed row. The employee directory
 * is queried first; on a miss the client directory is queried.</p>
 *
 * <p>Resolution is strictly best-effort: notifications must never crash the order
 * flow. Any directory-lookup failure is swallowed and the resulting payload simply
 * carries no {@code userEmail} (the in-app row still lands via
 * {@code recipientUserId}).</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationFactory {

    /** Recipient id-space discriminator for an employee/actuary order owner. */
    private static final String RECIPIENT_TYPE_EMPLOYEE = "EMPLOYEE";

    /** Recipient id-space discriminator for a client order owner. */
    private static final String RECIPIENT_TYPE_CLIENT = "CLIENT";

    private final EmployeeClient employeeClient;
    private final ClientClient clientClient;

    /**
     * Builds a notification payload for a standard order-lifecycle transition
     * (created, executed, cancelled).
     *
     * @param order  the order the event concerns
     * @param status the lifecycle status to advertise in the payload
     * @return a populated payload with the owner's contact details resolved
     */
    public OrderNotificationPayload build(Order order, OrderStatus status) {
        return populate(new OrderNotificationPayload(), order, status, null);
    }

    /**
     * Builds a notification payload for a partial-fill event, carrying the number
     * of units executed by this fill leg.
     *
     * @param order          the order being partially filled
     * @param filledQuantity number of units executed by this fill
     * @return a populated payload with the owner's contact details resolved
     */
    public OrderNotificationPayload buildPartialFill(Order order, Integer filledQuantity) {
        return populate(new OrderNotificationPayload(), order, order.getStatus(), filledQuantity);
    }

    private OrderNotificationPayload populate(OrderNotificationPayload payload, Order order,
                                              OrderStatus status, Integer filledQuantity) {
        payload.setOrderId(order.getId());
        payload.setStatus(status);
        payload.setUserId(order.getUserId());
        payload.setListingId(order.getListingId());
        payload.setOrderType(order.getOrderType());
        payload.setDirection(order.getDirection());
        payload.setQuantity(order.getQuantity());
        payload.setFilledQuantity(filledQuantity);
        payload.setRecipientUserId(order.getUserId());
        supervisorIdInto(payload, order);

        resolveOwner(order.getUserId(), payload);

        Map<String, String> variables = new LinkedHashMap<>();
        putIfPresent(variables, "orderId", order.getId());
        putIfPresent(variables, "status", status);
        putIfPresent(variables, "userId", order.getUserId());
        putIfPresent(variables, "supervisorId", payload.getSupervisorId());
        putIfPresent(variables, "listingId", order.getListingId());
        putIfPresent(variables, "orderType", order.getOrderType());
        putIfPresent(variables, "direction", order.getDirection());
        putIfPresent(variables, "quantity", order.getQuantity());
        putIfPresent(variables, "filledQuantity", filledQuantity);
        payload.setTemplateVariables(variables);
        return payload;
    }

    /**
     * Carries the supervisor id (when set) into the payload — kept separate so the
     * {@code approvedBy} sentinels (-1 / -2) used by the order flow are not leaked
     * into the notification as a literal supervisor id.
     */
    private void supervisorIdInto(OrderNotificationPayload payload, Order order) {
        Long approvedBy = order.getApprovedBy();
        if (approvedBy != null && approvedBy >= 0) {
            payload.setSupervisorId(approvedBy);
        }
    }

    /**
     * Resolves the order owner's display name, email and recipient type, querying
     * the employee directory first and falling back to the client directory.
     *
     * @param ownerId the order owner's user id
     * @param payload payload to enrich with the resolved contact details
     */
    private void resolveOwner(Long ownerId, OrderNotificationPayload payload) {
        if (ownerId == null) {
            return;
        }
        EmployeeDto employee = lookupEmployee(ownerId);
        if (employee != null) {
            payload.setUsername(formatEmployeeName(employee));
            payload.setUserEmail(employee.getEmail());
            payload.setRecipientType(RECIPIENT_TYPE_EMPLOYEE);
            return;
        }
        CustomerDto customer = lookupCustomer(ownerId);
        if (customer != null) {
            payload.setUsername(formatCustomerName(customer));
            payload.setUserEmail(customer.getEmail());
            payload.setRecipientType(RECIPIENT_TYPE_CLIENT);
            return;
        }
        // Neither directory resolved the owner — leave email/type null. The in-app
        // row still lands via recipientUserId; email delivery is simply skipped.
        log.warn("Could not resolve order owner {} in employee or client directory — "
                + "notification will be sent without recipient contact details", ownerId);
    }

    private EmployeeDto lookupEmployee(Long ownerId) {
        try {
            return employeeClient.getEmployee(ownerId);
        } catch (RuntimeException ex) {
            log.debug("Employee lookup for order owner {} did not resolve: {}", ownerId, ex.toString());
            return null;
        }
    }

    private CustomerDto lookupCustomer(Long ownerId) {
        try {
            return clientClient.getCustomer(ownerId);
        } catch (RuntimeException ex) {
            log.debug("Client lookup for order owner {} did not resolve: {}", ownerId, ex.toString());
            return null;
        }
    }

    private String formatEmployeeName(EmployeeDto employee) {
        String firstName = employee.getIme() == null ? "" : employee.getIme().trim();
        String lastName = employee.getPrezime() == null ? "" : employee.getPrezime().trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? employee.getUsername() : fullName;
    }

    private String formatCustomerName(CustomerDto customer) {
        String firstName = customer.getFirstName() == null ? "" : customer.getFirstName().trim();
        String lastName = customer.getLastName() == null ? "" : customer.getLastName().trim();
        String fullName = (firstName + " " + lastName).trim();
        return fullName.isEmpty() ? null : fullName;
    }

    private void putIfPresent(Map<String, String> variables, String key, Object value) {
        if (value != null) {
            variables.put(key, value instanceof Enum<?> enumValue ? enumValue.name() : String.valueOf(value));
        }
    }
}
