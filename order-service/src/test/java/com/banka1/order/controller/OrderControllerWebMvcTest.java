package com.banka1.order.controller;

import com.banka1.order.advice.OrderServiceExceptionHandler;
import com.banka1.order.dto.MyOrdersFilter;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import com.banka1.order.exception.BusinessConflictException;
import com.banka1.order.exception.ForbiddenOperationException;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.service.OrderCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderControllerWebMvcTest {

    private OrderCreationService orderCreationService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        orderCreationService = mock(OrderCreationService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderCreationService))
                .setCustomArgumentResolvers(new JwtRequestAttributeResolver())
                .setValidator(validator)
                .setControllerAdvice(new OrderServiceExceptionHandler())
                .build();
    }

    @Test
    void missingOrder_returns404NotFound() throws Exception {
        when(orderCreationService.confirmOrder(any(), eq(99L)))
                .thenThrow(new ResourceNotFoundException("Order not found"));

        mockMvc.perform(post("/orders/99/confirm")
                        .requestAttr("jwt", jwtPrincipal(List.of("CLIENT_TRADING"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Order not found"))
                .andExpect(jsonPath("$.path").value("/orders/99/confirm"));
    }

    @Test
    void insufficientFunds_returns409Conflict() throws Exception {
        when(orderCreationService.confirmOrder(any(), eq(100L)))
                .thenThrow(new BusinessConflictException("Insufficient funds"));

        mockMvc.perform(post("/orders/100/confirm")
                        .requestAttr("jwt", jwtPrincipal(List.of("CLIENT_TRADING"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Insufficient funds"));
    }

    @Test
    void approvingNonPendingOrder_returns409Conflict() throws Exception {
        when(orderCreationService.approveOrder(eq(7L), eq(100L)))
                .thenThrow(new BusinessConflictException("Only pending orders can be approved"));

        mockMvc.perform(put("/orders/100/approve")
                        .requestAttr("jwt", jwtPrincipal("7", List.of("SUPERVISOR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only pending orders can be approved"));
    }

    @Test
    void approvingExpiredPendingOrder_returns409Conflict() throws Exception {
        when(orderCreationService.approveOrder(eq(7L), eq(100L)))
                .thenThrow(new BusinessConflictException("Orders with past settlement date can only be declined"));

        mockMvc.perform(put("/orders/100/approve")
                        .requestAttr("jwt", jwtPrincipal("7", List.of("SUPERVISOR"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Orders with past settlement date can only be declined"));
    }

    @Test
    void wrongUserOrderAccess_returns403Forbidden() throws Exception {
        when(orderCreationService.cancelOrder(any(), eq(100L)))
                .thenThrow(new ForbiddenOperationException("Order does not belong to the authenticated user"));

        mockMvc.perform(post("/orders/100/cancel")
                        .requestAttr("jwt", jwtPrincipal(List.of("CLIENT_TRADING"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Order does not belong to the authenticated user"));
    }

    @Test
    void happyPathStillReturns200() throws Exception {
        OrderResponse response = new OrderResponse();
        response.setId(100L);
        response.setStatus(OrderStatus.APPROVED);
        response.setDirection(OrderDirection.BUY);
        response.setOrderType(OrderType.MARKET);
        when(orderCreationService.confirmOrder(any(), eq(100L))).thenReturn(response);

        mockMvc.perform(post("/orders/100/confirm")
                        .requestAttr("jwt", jwtPrincipal(List.of("CLIENT_TRADING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void myOrders_returnsAuthenticatedClientOrders() throws Exception {
        OrderResponse ownOrder = new OrderResponse();
        ownOrder.setId(100L);
        ownOrder.setUserId(42L);
        ownOrder.setStatus(OrderStatus.APPROVED);
        ownOrder.setDirection(OrderDirection.BUY);
        ownOrder.setOrderType(OrderType.MARKET);
        ownOrder.setFee(new BigDecimal("7.00"));
        when(orderCreationService.getMyOrders(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ownOrder)));

        mockMvc.perform(get("/orders/my-orders")
                        .requestAttr("jwt", jwtPrincipal("42", List.of("CLIENT_TRADING"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(100))
                .andExpect(jsonPath("$.content[0].userId").value(42))
                .andExpect(jsonPath("$.content[0].status").value("APPROVED"))
                .andExpect(jsonPath("$.content[0].fee").value(7.00));
    }

    @Test
    void myOrders_agentCanSeeOwnOrders() throws Exception {
        OrderResponse agentOrder = new OrderResponse();
        agentOrder.setId(200L);
        agentOrder.setUserId(7L);
        agentOrder.setStatus(OrderStatus.DONE);
        agentOrder.setDirection(OrderDirection.SELL);
        agentOrder.setOrderType(OrderType.LIMIT);
        when(orderCreationService.getMyOrders(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(agentOrder)));

        mockMvc.perform(get("/orders/my-orders")
                        .requestAttr("jwt", jwtPrincipal("7", List.of("AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(200))
                .andExpect(jsonPath("$.content[0].userId").value(7));
    }

    @Test
    void myOrders_passesFilterAndPagingParamsToService() throws Exception {
        when(orderCreationService.getMyOrders(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/orders/my-orders")
                        .param("status", "DONE")
                        .param("direction", "SELL")
                        .param("securityType", "FUTURES")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .param("page", "1")
                        .param("size", "5")
                        .requestAttr("jwt", jwtPrincipal("42", List.of("CLIENT_TRADING"))))
                .andExpect(status().isOk());

        ArgumentCaptor<MyOrdersFilter> filterCaptor = ArgumentCaptor.forClass(MyOrdersFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderCreationService).getMyOrders(any(), filterCaptor.capture(), pageableCaptor.capture());

        MyOrdersFilter filter = filterCaptor.getValue();
        assertThat(filter.status()).isEqualTo(OrderStatus.DONE);
        assertThat(filter.direction()).isEqualTo(OrderDirection.SELL);
        assertThat(filter.listingType()).isEqualTo(ListingType.FUTURES);
        assertThat(filter.from()).isEqualTo(LocalDate.of(2026, 5, 1).atStartOfDay());
        assertThat(filter.to()).isEqualTo(LocalDate.of(2026, 5, 31).atTime(LocalTime.MAX));
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void myOrders_noParams_usesDefaultPaging() throws Exception {
        when(orderCreationService.getMyOrders(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/orders/my-orders")
                        .requestAttr("jwt", jwtPrincipal("42", List.of("CLIENT_TRADING"))))
                .andExpect(status().isOk());

        ArgumentCaptor<MyOrdersFilter> filterCaptor = ArgumentCaptor.forClass(MyOrdersFilter.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(orderCreationService).getMyOrders(any(), filterCaptor.capture(), pageableCaptor.capture());

        MyOrdersFilter filter = filterCaptor.getValue();
        assertThat(filter.status()).isNull();
        assertThat(filter.direction()).isNull();
        assertThat(filter.listingType()).isNull();
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    void myOrders_forbiddenUser_returns403Forbidden() throws Exception {
        when(orderCreationService.getMyOrders(any(), any(), any(Pageable.class)))
                .thenThrow(new ForbiddenOperationException("Only clients and agents can view their orders"));

        mockMvc.perform(get("/orders/my-orders")
                        .requestAttr("jwt", jwtPrincipal("7", List.of("CLIENT_TRADING"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only clients and agents can view their orders"));
    }

    @Test
    void invalidBuyPayload_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/orders/buy")
                        .requestAttr("jwt", jwtPrincipal(List.of("CLIENT_TRADING")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "listingId": null,
                                  "quantity": 0,
                                  "accountId": -5,
                                  "limitValue": -10
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.listingId").exists())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists())
                .andExpect(jsonPath("$.fieldErrors.limitValue").exists());

        verifyNoInteractions(orderCreationService);
    }

    @Test
    void invalidSellPayload_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/orders/sell")
                        .requestAttr("jwt", jwtPrincipal(List.of("AGENT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "listingId": 0,
                                  "quantity": -1,
                                  "accountId": null,
                                  "stopValue": -2
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.listingId").exists())
                .andExpect(jsonPath("$.fieldErrors.quantity").exists())
                .andExpect(jsonPath("$.fieldErrors.accountId").exists())
                .andExpect(jsonPath("$.fieldErrors.stopValue").exists());

        verifyNoInteractions(orderCreationService);
    }

    private Jwt jwtPrincipal(List<String> roles) {
        return jwtPrincipal("42", roles);
    }

    private Jwt jwtPrincipal(String subject, List<String> roles) {
        return Jwt.withTokenValue("token")
                .subject(subject)
                .claim("roles", roles)
                .claim("permissions", List.of())
                .header("alg", "none")
                .build();
    }

    private static final class JwtRequestAttributeResolver implements HandlerMethodArgumentResolver {

        @Override
        public boolean supportsParameter(MethodParameter parameter) {
            return parameter.getParameterType().equals(Jwt.class);
        }

        @Override
        public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                      NativeWebRequest webRequest, org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
            return webRequest.getAttribute("jwt", RequestAttributes.SCOPE_REQUEST);
        }
    }
}
