package com.banka1.tradingservice.otc.notification;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.EmployeeDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WP-15 (Celina 4.1): publishes OTC negotiation notification events to the
 * shared {@code ${rabbitmq.exchange}} topic exchange (= {@code NOTIFICATION_EXCHANGE},
 * default {@code employee.events}).
 *
 * <p>The notification-service binds an {@code otc.#} queue and maps the routing
 * keys below to its notification templates:
 * <ul>
 *   <li>{@code otc.counter_offer}     &rarr; {@code OTC_COUNTER_OFFER}</li>
 *   <li>{@code otc.accepted}          &rarr; {@code OTC_ACCEPTED}</li>
 *   <li>{@code otc.rejected}          &rarr; {@code OTC_REJECTED}</li>
 *   <li>{@code otc.withdrawn}         &rarr; {@code OTC_WITHDRAWN}</li>
 *   <li>{@code otc.contract_expiring} &rarr; {@code OTC_CONTRACT_EXPIRING}</li>
 * </ul>
 *
 * <p>The recipient of an OTC notification is always the <em>counterparty</em> —
 * the user who did not perform the action (or, for the expiry reminder, the
 * option-contract buyer). OTC negotiations are normally between bank clients,
 * but a participant can also be an actuary (an employee). The recipient's
 * display name, email and id space are resolved best-effort: the employee
 * directory is queried first, then the client directory. A null email is
 * acceptable — the in-app channel still delivers via {@code recipientUserId}.
 *
 * <p>Publishing is strictly best-effort: a notification must never crash the
 * OTC flow. Any send or resolution failure is logged and swallowed.
 */
@Slf4j
@Component
public class OtcNotificationProducer {

    /** Routing key for "the other party sent a counter-offer". */
    public static final String RK_COUNTER_OFFER = "otc.counter_offer";

    /** Routing key for "the other party accepted the offer". */
    public static final String RK_ACCEPTED = "otc.accepted";

    /** Routing key for "the other party rejected the offer". */
    public static final String RK_REJECTED = "otc.rejected";

    /** Routing key for "the other party gave up on (withdrew) the offer". */
    public static final String RK_WITHDRAWN = "otc.withdrawn";

    /** Routing key for "an option contract is expiring soon". */
    public static final String RK_CONTRACT_EXPIRING = "otc.contract_expiring";

    /** Recipient id-space discriminator for a client counterparty. */
    private static final String RECIPIENT_TYPE_CLIENT = "CLIENT";

    /** Recipient id-space discriminator for an employee/actuary counterparty. */
    private static final String RECIPIENT_TYPE_EMPLOYEE = "EMPLOYEE";

    /** Template placeholder key for the negotiation/offer identifier. */
    private static final String VAR_NEGOTIATION_ID = "negotiationId";

    /** Template placeholder key for the option-contract identifier. */
    private static final String VAR_CONTRACT_ID = "contractId";

    /** Template placeholder key for the option-contract expiry date. */
    private static final String VAR_EXPIRY_DATE = "expiryDate";

    private final RabbitTemplate rabbitTemplate;
    private final EmployeeClient employeeClient;
    private final ClientClient clientClient;
    private final String exchange;

    public OtcNotificationProducer(RabbitTemplate rabbitTemplate,
                                   EmployeeClient employeeClient,
                                   ClientClient clientClient,
                                   @Value("${rabbitmq.exchange}") String exchange) {
        this.rabbitTemplate = rabbitTemplate;
        this.employeeClient = employeeClient;
        this.clientClient = clientClient;
        this.exchange = exchange;
    }

    /**
     * Notifies the OTC counterparty that a counter-offer was sent on an offer.
     *
     * @param offerId       the OTC offer/negotiation id
     * @param counterpartyId the user now waited on (the offer's other party)
     */
    public void notifyCounterOffer(Long offerId, Long counterpartyId) {
        publish(RK_COUNTER_OFFER, counterpartyId, negotiationVariables(offerId));
    }

    /**
     * Notifies the OTC counterparty that their offer was accepted.
     *
     * @param offerId        the OTC offer/negotiation id
     * @param counterpartyId the user who did not perform the accept
     */
    public void notifyAccepted(Long offerId, Long counterpartyId) {
        publish(RK_ACCEPTED, counterpartyId, negotiationVariables(offerId));
    }

    /**
     * Notifies the OTC counterparty that their offer was rejected.
     *
     * @param offerId        the OTC offer/negotiation id
     * @param counterpartyId the user who did not perform the reject
     */
    public void notifyRejected(Long offerId, Long counterpartyId) {
        publish(RK_REJECTED, counterpartyId, negotiationVariables(offerId));
    }

    /**
     * Notifies the OTC counterparty that the offer was withdrawn (given up on).
     *
     * @param offerId        the OTC offer/negotiation id
     * @param counterpartyId the user who did not perform the withdraw
     */
    public void notifyWithdrawn(Long offerId, Long counterpartyId) {
        publish(RK_WITHDRAWN, counterpartyId, negotiationVariables(offerId));
    }

    /**
     * Notifies the option-contract buyer that the contract is expiring soon.
     *
     * @param contractId   the option-contract id
     * @param buyerId      the contract buyer (notification recipient)
     * @param expiryDate   the contract settlement/expiry date
     */
    public void notifyContractExpiring(Long contractId, Long buyerId, LocalDate expiryDate) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(VAR_CONTRACT_ID, String.valueOf(contractId));
        if (expiryDate != null) {
            variables.put(VAR_EXPIRY_DATE, expiryDate.toString());
        }
        publish(RK_CONTRACT_EXPIRING, buyerId, variables);
    }

    private Map<String, String> negotiationVariables(Long offerId) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put(VAR_NEGOTIATION_ID, String.valueOf(offerId));
        return variables;
    }

    /**
     * Resolves the recipient's contact details and publishes the payload.
     * Failures are logged and swallowed — notifications never break the OTC flow.
     */
    private void publish(String routingKey, Long recipientId, Map<String, String> templateVariables) {
        try {
            OtcNotificationPayload payload = buildPayload(recipientId, templateVariables);
            rabbitTemplate.convertAndSend(exchange, routingKey, payload);
            log.debug("OTC notification published: exchange={} key={} recipient={}",
                    exchange, routingKey, recipientId);
        } catch (Exception ex) {
            log.error("OTC notification publish failed (key={}, recipient={}): {}",
                    routingKey, recipientId, ex.toString(), ex);
        }
    }

    /**
     * Builds the notification payload, resolving the recipient's display name,
     * email and id space. The employee directory is queried first; on a miss
     * the client directory is queried. When neither resolves, the payload
     * carries no email/type and only the in-app row lands via the recipient id.
     */
    private OtcNotificationPayload buildPayload(Long recipientId, Map<String, String> templateVariables) {
        EmployeeDto employee = lookupEmployee(recipientId);
        if (employee != null) {
            return new OtcNotificationPayload(
                    formatEmployeeName(employee), employee.getEmail(), templateVariables,
                    recipientId, RECIPIENT_TYPE_EMPLOYEE, null);
        }
        CustomerDto customer = lookupCustomer(recipientId);
        if (customer != null) {
            return new OtcNotificationPayload(
                    formatCustomerName(customer), customer.getEmail(), templateVariables,
                    recipientId, RECIPIENT_TYPE_CLIENT, recipientId);
        }
        // Neither directory resolved the counterparty — leave email/type null.
        // The in-app row still lands via recipientUserId; email is simply skipped.
        log.warn("Could not resolve OTC counterparty {} in employee or client directory — "
                + "notification will be sent without recipient contact details", recipientId);
        return new OtcNotificationPayload(null, null, templateVariables, recipientId, null, null);
    }

    private EmployeeDto lookupEmployee(Long recipientId) {
        if (recipientId == null) {
            return null;
        }
        try {
            return employeeClient.getEmployee(recipientId);
        } catch (RuntimeException ex) {
            log.debug("Employee lookup for OTC counterparty {} did not resolve: {}", recipientId, ex.toString());
            return null;
        }
    }

    private CustomerDto lookupCustomer(Long recipientId) {
        if (recipientId == null) {
            return null;
        }
        try {
            return clientClient.getCustomer(recipientId);
        } catch (RuntimeException ex) {
            log.debug("Client lookup for OTC counterparty {} did not resolve: {}", recipientId, ex.toString());
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
}
