package com.banka1.order.rabbitmq;

import com.banka1.order.client.ClientClient;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.CustomerDto;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.OrderNotificationPayload;
import com.banka1.order.entity.Order;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link OrderNotificationFactory} resolves the order owner's email and
 * recipient type, and never lets a lookup failure escape into the order flow.
 */
@ExtendWith(MockitoExtension.class)
class OrderNotificationFactoryTest {

    @Mock
    private EmployeeClient employeeClient;
    @Mock
    private ClientClient clientClient;

    @InjectMocks
    private OrderNotificationFactory factory;

    private Order order;

    @BeforeEach
    void setUp() {
        order = new Order();
        order.setId(100L);
        order.setUserId(7L);
        order.setListingId(42L);
        order.setOrderType(OrderType.MARKET);
        order.setDirection(OrderDirection.BUY);
        order.setQuantity(10);
        order.setStatus(OrderStatus.PENDING_CONFIRMATION);
    }

    @Test
    void build_resolvesEmployeeOwnerEmailAndType() {
        EmployeeDto employee = new EmployeeDto();
        employee.setId(7L);
        employee.setIme("Ana");
        employee.setPrezime("Agent");
        employee.setEmail("ana.agent@example.com");
        when(employeeClient.getEmployee(7L)).thenReturn(employee);

        OrderNotificationPayload payload = factory.build(order, OrderStatus.PENDING_CONFIRMATION);

        assertThat(payload.getUserEmail()).isEqualTo("ana.agent@example.com");
        assertThat(payload.getUsername()).isEqualTo("Ana Agent");
        assertThat(payload.getRecipientType()).isEqualTo("EMPLOYEE");
        assertThat(payload.getRecipientUserId()).isEqualTo(7L);
        assertThat(payload.getOrderId()).isEqualTo(100L);
        assertThat(payload.getListingId()).isEqualTo(42L);
        assertThat(payload.getStatus()).isEqualTo(OrderStatus.PENDING_CONFIRMATION);
        // Templates render {{orderId}}/{{listingId}} from templateVariables.
        assertThat(payload.getTemplateVariables()).containsEntry("orderId", "100");
        assertThat(payload.getTemplateVariables()).containsEntry("listingId", "42");
        verifyNoInteractions(clientClient);
    }

    @Test
    void build_fallsBackToClientWhenEmployeeLookupReturns404() {
        when(employeeClient.getEmployee(7L))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        CustomerDto customer = new CustomerDto();
        customer.setId(7L);
        customer.setFirstName("Marko");
        customer.setLastName("Markovic");
        customer.setEmail("marko@example.com");
        when(clientClient.getCustomer(7L)).thenReturn(customer);

        OrderNotificationPayload payload = factory.build(order, OrderStatus.APPROVED);

        assertThat(payload.getUserEmail()).isEqualTo("marko@example.com");
        assertThat(payload.getUsername()).isEqualTo("Marko Markovic");
        assertThat(payload.getRecipientType()).isEqualTo("CLIENT");
        assertThat(payload.getRecipientUserId()).isEqualTo(7L);
    }

    @Test
    void build_returnsPayloadWithoutEmailWhenBothLookupsFail() {
        when(employeeClient.getEmployee(7L)).thenThrow(new RuntimeException("employee-service down"));
        when(clientClient.getCustomer(7L)).thenThrow(new RuntimeException("client-service down"));

        OrderNotificationPayload payload = factory.build(order, OrderStatus.CANCELLED);

        // A failed lookup must not crash the order flow — the payload still carries
        // the owner id so the in-app row can land best-effort; email is simply absent.
        assertThat(payload.getUserEmail()).isNull();
        assertThat(payload.getRecipientUserId()).isEqualTo(7L);
        assertThat(payload.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void build_carriesSupervisorIdWhenApprovedByIsARealSupervisor() {
        // approvedBy holds the supervisor id once a real supervisor decided the order
        // (the -1/-2 sentinels are filtered out — see build_doesNotLeakApprovalSentinel).
        order.setApprovedBy(88L);
        EmployeeDto employee = new EmployeeDto();
        employee.setId(7L);
        employee.setEmail("ana@example.com");
        employee.setUsername("aagent");
        when(employeeClient.getEmployee(7L)).thenReturn(employee);

        OrderNotificationPayload payload = factory.build(order, OrderStatus.APPROVED);

        assertThat(payload.getSupervisorId()).isEqualTo(88L);
        assertThat(payload.getTemplateVariables()).containsEntry("supervisorId", "88");
    }

    @Test
    void build_doesNotLeakApprovalSentinelAsSupervisorId() {
        // NO_APPROVAL_REQUIRED (-1) / SYSTEM_APPROVAL (-2) are internal markers, not
        // real supervisor ids — they must never surface in the notification payload.
        order.setApprovedBy(-1L);
        EmployeeDto employee = new EmployeeDto();
        employee.setId(7L);
        employee.setEmail("ana@example.com");
        employee.setUsername("aagent");
        when(employeeClient.getEmployee(7L)).thenReturn(employee);

        OrderNotificationPayload payload = factory.build(order, OrderStatus.APPROVED);

        assertThat(payload.getSupervisorId()).isNull();
        assertThat(payload.getTemplateVariables()).doesNotContainKey("supervisorId");
    }

    @Test
    void build_leavesUsernameNullWhenClientHasNoNameFields() {
        when(employeeClient.getEmployee(7L))
                .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));
        CustomerDto customer = new CustomerDto();
        customer.setId(7L);
        customer.setEmail("noname@example.com");
        when(clientClient.getCustomer(7L)).thenReturn(customer);

        OrderNotificationPayload payload = factory.build(order, OrderStatus.CANCELLED);

        // Email still resolves; the display name is simply absent.
        assertThat(payload.getUserEmail()).isEqualTo("noname@example.com");
        assertThat(payload.getUsername()).isNull();
        assertThat(payload.getRecipientType()).isEqualTo("CLIENT");
    }

    @Test
    void build_carriesFilledQuantityWhenProvided() {
        EmployeeDto employee = new EmployeeDto();
        employee.setId(7L);
        employee.setEmail("ana@example.com");
        employee.setUsername("aagent");
        lenient().when(employeeClient.getEmployee(7L)).thenReturn(employee);

        OrderNotificationPayload payload = factory.buildPartialFill(order, 3);

        assertThat(payload.getFilledQuantity()).isEqualTo(3);
        assertThat(payload.getTemplateVariables()).containsEntry("filledQuantity", "3");
        assertThat(payload.getQuantity()).isEqualTo(10);
    }
}
