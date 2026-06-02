package com.banka1.order.controller;

import com.banka1.order.advice.OrderServiceExceptionHandler;
import com.banka1.order.dto.CreateRecurringOrderRequest;
import com.banka1.order.dto.RecurringOrderDto;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.service.RecurringOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RecurringOrderControllerWebMvcTest {

    private RecurringOrderService recurringOrderService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        recurringOrderService = mock(RecurringOrderService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new RecurringOrderController(recurringOrderService))
                .setCustomArgumentResolvers(new JwtRequestAttributeResolver())
                .setValidator(validator)
                .setControllerAdvice(new OrderServiceExceptionHandler())
                .build();
    }

    @Test
    void getMyRecurringOrders_returnsCallersStandingOrders() throws Exception {
        when(recurringOrderService.getForUser(42L)).thenReturn(List.of(sampleDto(1L, 42L)));

        mockMvc.perform(get("/recurring-orders")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].userId").value(42))
                .andExpect(jsonPath("$[0].mode").value("BY_AMOUNT"))
                .andExpect(jsonPath("$[0].cadence").value("MONTHLY"));

        verify(recurringOrderService).getForUser(42L);
    }

    @Test
    void createRecurringOrder_returns201WithCreatedOrder() throws Exception {
        when(recurringOrderService.create(eq(42L), any(CreateRecurringOrderRequest.class)))
                .thenReturn(sampleDto(7L, 42L));

        mockMvc.perform(post("/recurring-orders")
                        .requestAttr("jwt", jwtPrincipal(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "listingId": 42,
                                  "direction": "BUY",
                                  "mode": "BY_AMOUNT",
                                  "value": 10000.00,
                                  "accountId": 5,
                                  "cadence": "MONTHLY",
                                  "nextRun": "2099-01-01T00:00:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.userId").value(42));

        ArgumentCaptor<CreateRecurringOrderRequest> captor =
                ArgumentCaptor.forClass(CreateRecurringOrderRequest.class);
        verify(recurringOrderService).create(eq(42L), captor.capture());
        assertThat(captor.getValue().getListingId()).isEqualTo(42L);
        assertThat(captor.getValue().getMode()).isEqualTo(RecurringMode.BY_AMOUNT);
        assertThat(captor.getValue().getCadence()).isEqualTo(RecurringCadence.MONTHLY);
    }

    @Test
    void createRecurringOrder_invalidPayload_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/recurring-orders")
                        .requestAttr("jwt", jwtPrincipal(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "listingId": null,
                                  "value": -10,
                                  "accountId": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.fieldErrors.listingId").exists())
                .andExpect(jsonPath("$.fieldErrors.direction").exists())
                .andExpect(jsonPath("$.fieldErrors.mode").exists())
                .andExpect(jsonPath("$.fieldErrors.value").exists())
                .andExpect(jsonPath("$.fieldErrors.cadence").exists())
                .andExpect(jsonPath("$.fieldErrors.nextRun").exists());
    }

    @Test
    void pauseRecurringOrder_returns200() throws Exception {
        RecurringOrderDto paused = sampleDto(3L, 42L);
        paused.setActive(false);
        when(recurringOrderService.pause(42L, 3L)).thenReturn(paused);

        mockMvc.perform(patch("/recurring-orders/3/pause")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(recurringOrderService).pause(42L, 3L);
    }

    @Test
    void resumeRecurringOrder_returns200() throws Exception {
        when(recurringOrderService.resume(42L, 3L)).thenReturn(sampleDto(3L, 42L));

        mockMvc.perform(patch("/recurring-orders/3/resume")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));

        verify(recurringOrderService).resume(42L, 3L);
    }

    @Test
    void cancelRecurringOrder_returns204() throws Exception {
        doNothing().when(recurringOrderService).cancel(42L, 3L);

        mockMvc.perform(delete("/recurring-orders/3")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isNoContent());

        verify(recurringOrderService).cancel(42L, 3L);
    }

    @Test
    void pauseRecurringOrder_notOwned_returns404() throws Exception {
        when(recurringOrderService.pause(42L, 99L))
                .thenThrow(new ResourceNotFoundException("Recurring order not found"));

        mockMvc.perform(patch("/recurring-orders/99/pause")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Recurring order not found"));
    }

    @Test
    void cancelRecurringOrder_notOwned_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Recurring order not found"))
                .when(recurringOrderService).cancel(42L, 99L);

        mockMvc.perform(delete("/recurring-orders/99")
                        .requestAttr("jwt", jwtPrincipal(42L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Recurring order not found"));
    }

    private RecurringOrderDto sampleDto(Long id, Long userId) {
        RecurringOrderDto dto = new RecurringOrderDto();
        dto.setId(id);
        dto.setUserId(userId);
        dto.setListingId(42L);
        dto.setDirection(OrderDirection.BUY);
        dto.setMode(RecurringMode.BY_AMOUNT);
        dto.setValue(new BigDecimal("10000.00"));
        dto.setAccountId(5L);
        dto.setCadence(RecurringCadence.MONTHLY);
        dto.setNextRun(LocalDateTime.now().plusDays(7));
        dto.setActive(true);
        dto.setCreatedAt(LocalDateTime.now());
        return dto;
    }

    private Jwt jwtPrincipal(Long id) {
        return Jwt.withTokenValue("token")
                .subject(String.valueOf(id))
                .claim("id", id)
                .claim("roles", List.of("CLIENT_TRADING"))
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
                                      NativeWebRequest webRequest,
                                      WebDataBinderFactory binderFactory) {
            return webRequest.getAttribute("jwt", RequestAttributes.SCOPE_REQUEST);
        }
    }
}
