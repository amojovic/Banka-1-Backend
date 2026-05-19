package com.banka1.tradingservice.otc.notification;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.EmployeeDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-15 (Celina 4.1): verifies that {@link OtcNotificationProducer} publishes the
 * correct routing keys and payloads, resolves the recipient via the employee /
 * client directories, and never lets a failure escape.
 */
@ExtendWith(MockitoExtension.class)
class OtcNotificationProducerTest {

    private static final String EXCHANGE = "employee.events";

    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private EmployeeClient employeeClient;
    @Mock private ClientClient clientClient;

    private OtcNotificationProducer producer() {
        return new OtcNotificationProducer(rabbitTemplate, employeeClient, clientClient, EXCHANGE);
    }

    @Test
    void notifyCounterOffer_publishesCounterOfferKeyWithNegotiationId() {
        when(employeeClient.getEmployee(7L)).thenReturn(null);
        when(clientClient.getCustomer(7L)).thenReturn(customer("Mila", "Mojovic", "mila@x.rs"));

        producer().notifyCounterOffer(42L, 7L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_COUNTER_OFFER);
        assertThat(payload.templateVariables()).containsEntry("negotiationId", "42");
        assertThat(payload.recipientUserId()).isEqualTo(7L);
        assertThat(payload.recipientType()).isEqualTo("CLIENT");
        assertThat(payload.clientId()).isEqualTo(7L);
        assertThat(payload.username()).isEqualTo("Mila Mojovic");
        assertThat(payload.userEmail()).isEqualTo("mila@x.rs");
    }

    @Test
    void notifyAccepted_publishesAcceptedKey() {
        when(employeeClient.getEmployee(anyLong())).thenReturn(null);
        when(clientClient.getCustomer(anyLong())).thenReturn(customer("Pera", "Peric", "pera@x.rs"));

        producer().notifyAccepted(100L, 5L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_ACCEPTED);
        assertThat(payload.templateVariables()).containsEntry("negotiationId", "100");
        assertThat(payload.recipientUserId()).isEqualTo(5L);
    }

    @Test
    void notifyRejected_publishesRejectedKey() {
        when(employeeClient.getEmployee(anyLong())).thenReturn(null);
        when(clientClient.getCustomer(anyLong())).thenReturn(customer("Pera", "Peric", "pera@x.rs"));

        producer().notifyRejected(101L, 6L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_REJECTED);
        assertThat(payload.templateVariables()).containsEntry("negotiationId", "101");
        assertThat(payload.recipientUserId()).isEqualTo(6L);
    }

    @Test
    void notifyWithdrawn_publishesWithdrawnKey() {
        when(employeeClient.getEmployee(anyLong())).thenReturn(null);
        when(clientClient.getCustomer(anyLong())).thenReturn(customer("Pera", "Peric", "pera@x.rs"));

        producer().notifyWithdrawn(102L, 9L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_WITHDRAWN);
        assertThat(payload.templateVariables()).containsEntry("negotiationId", "102");
        assertThat(payload.recipientUserId()).isEqualTo(9L);
    }

    @Test
    void notifyContractExpiring_publishesExpiringKeyWithContractIdAndExpiryDate() {
        LocalDate expiry = LocalDate.of(2026, 6, 1);
        when(employeeClient.getEmployee(anyLong())).thenReturn(null);
        when(clientClient.getCustomer(anyLong())).thenReturn(customer("Pera", "Peric", "pera@x.rs"));

        producer().notifyContractExpiring(500L, 3L, expiry);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_CONTRACT_EXPIRING);
        assertThat(payload.templateVariables()).containsEntry("contractId", "500");
        assertThat(payload.templateVariables()).containsEntry("expiryDate", "2026-06-01");
        assertThat(payload.recipientUserId()).isEqualTo(3L);
    }

    @Test
    void resolvesEmployeeRecipient_whenCounterpartyIsActuary() {
        EmployeeDto actuary = new EmployeeDto();
        actuary.setIme("Aki");
        actuary.setPrezime("Aktuar");
        actuary.setEmail("aki@banka.rs");
        actuary.setUsername("aki");
        when(employeeClient.getEmployee(11L)).thenReturn(actuary);

        producer().notifyAccepted(70L, 11L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_ACCEPTED);
        assertThat(payload.recipientType()).isEqualTo("EMPLOYEE");
        assertThat(payload.username()).isEqualTo("Aki Aktuar");
        assertThat(payload.userEmail()).isEqualTo("aki@banka.rs");
        // Employees do not get push (FCM) — clientId stays null.
        assertThat(payload.clientId()).isNull();
    }

    @Test
    void publishesWithoutContactDetails_whenRecipientResolvesInNeitherDirectory() {
        when(employeeClient.getEmployee(99L)).thenReturn(null);
        when(clientClient.getCustomer(99L)).thenReturn(null);

        producer().notifyRejected(80L, 99L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_REJECTED);
        // In-app row still lands via recipientUserId; email/type stay null.
        assertThat(payload.recipientUserId()).isEqualTo(99L);
        assertThat(payload.userEmail()).isNull();
        assertThat(payload.recipientType()).isNull();
    }

    @Test
    void swallowsDirectoryFailure_andStillPublishes() {
        when(employeeClient.getEmployee(anyLong()))
                .thenThrow(new RuntimeException("employee-service down"));
        when(clientClient.getCustomer(anyLong()))
                .thenThrow(new RuntimeException("client-service down"));

        // Must not throw — notifications never crash the OTC flow.
        producer().notifyCounterOffer(1L, 2L);

        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_COUNTER_OFFER);
        assertThat(payload.recipientUserId()).isEqualTo(2L);
    }

    @Test
    void swallowsBrokerPublishFailure() {
        when(employeeClient.getEmployee(anyLong())).thenReturn(null);
        when(clientClient.getCustomer(anyLong())).thenReturn(customer("Pera", "Peric", "pera@x.rs"));
        doThrow(new RuntimeException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(OtcNotificationProducer.RK_ACCEPTED), any(Object.class));

        // A broker failure must be swallowed, not propagated.
        producer().notifyAccepted(1L, 2L);
    }

    @Test
    void skipsDirectoryLookups_whenRecipientIdIsNull() {
        producer().notifyAccepted(1L, null);

        verifyNoInteractions(employeeClient);
        verifyNoInteractions(clientClient);
        OtcNotificationPayload payload = capturePublished(OtcNotificationProducer.RK_ACCEPTED);
        assertThat(payload.recipientUserId()).isNull();
    }

    private OtcNotificationPayload capturePublished(String routingKey) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(eq(EXCHANGE), eq(routingKey), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(OtcNotificationPayload.class);
        return (OtcNotificationPayload) captor.getValue();
    }

    private static CustomerDto customer(String first, String last, String email) {
        CustomerDto c = new CustomerDto();
        c.setFirstName(first);
        c.setLastName(last);
        c.setEmail(email);
        return c;
    }
}
