package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.PriceAlertCondition;
import com.banka1.stock_service.dto.CreatePriceAlertRequest;
import com.banka1.stock_service.dto.PriceAlertDto;
import com.banka1.stock_service.service.PriceAlertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link PriceAlertController}.
 */
@WebMvcTest(PriceAlertController.class)
@AutoConfigureMockMvc
@Import(PriceAlertControllerWebMvcTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class PriceAlertControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PriceAlertService priceAlertService;

    @Test
    void getMyAlertsReturnsCallerAlerts() throws Exception {
        when(priceAlertService.getAlertsForUser(5L)).thenReturn(List.of(sampleDto(1L, 5L, "CLIENT")));

        mockMvc.perform(get("/price-alerts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].userId").value(5))
                .andExpect(jsonPath("$[0].condition").value("ABOVE"));

        verify(priceAlertService).getAlertsForUser(5L);
    }

    @Test
    void getMyAlertsRejectsUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/price-alerts"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createAlertReturnsCreatedWithClientRecipientTypeForClientRole() throws Exception {
        when(priceAlertService.createAlert(eq(5L), eq("CLIENT"), any(CreatePriceAlertRequest.class)))
                .thenReturn(sampleDto(7L, 5L, "CLIENT"));

        String request = "{\"listingId\":15,\"condition\":\"ABOVE\","
                + "\"threshold\":200.0000,\"notificationType\":\"ALL\"}";

        mockMvc.perform(post("/price-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.recipientType").value("CLIENT"));

        verify(priceAlertService).createAlert(eq(5L), eq("CLIENT"), any(CreatePriceAlertRequest.class));
    }

    @Test
    void createAlertAcceptsClientTradingRole() throws Exception {
        when(priceAlertService.createAlert(eq(5L), eq("CLIENT"), any(CreatePriceAlertRequest.class)))
                .thenReturn(sampleDto(7L, 5L, "CLIENT"));

        String request = "{\"listingId\":15,\"condition\":\"ABOVE\","
                + "\"threshold\":200.0000,\"notificationType\":\"ALL\"}";

        mockMvc.perform(post("/price-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_TRADING"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isCreated());
    }

    @Test
    void createAlertDerivesEmployeeRecipientTypeForEmployeeRole() throws Exception {
        when(priceAlertService.createAlert(eq(44L), eq("EMPLOYEE"), any(CreatePriceAlertRequest.class)))
                .thenReturn(sampleDto(8L, 44L, "EMPLOYEE"));

        String request = "{\"listingId\":15,\"condition\":\"BELOW\","
                + "\"threshold\":100.0000,\"notificationType\":\"EMAIL\"}";

        mockMvc.perform(post("/price-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))
                                .jwt(token -> token.claim("id", 44L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.recipientType").value("EMPLOYEE"));

        verify(priceAlertService).createAlert(eq(44L), eq("EMPLOYEE"), any(CreatePriceAlertRequest.class));
    }

    @Test
    void createAlertRejectsInvalidRequestBody() throws Exception {
        String invalidJson = "{\"listingId\":15,\"condition\":\"ABOVE\",\"notificationType\":\"\"}";

        mockMvc.perform(post("/price-alerts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void toggleAlertReturnsUpdatedAlert() throws Exception {
        PriceAlertDto toggled = new PriceAlertDto(1L, 5L, "CLIENT", 15L,
                PriceAlertCondition.ABOVE, new BigDecimal("200.0000"), "ALL", false,
                LocalDateTime.of(2026, 5, 18, 12, 0), null);
        when(priceAlertService.toggleAlert(5L, 1L)).thenReturn(toggled);

        mockMvc.perform(patch("/price-alerts/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        verify(priceAlertService).toggleAlert(5L, 1L);
    }

    @Test
    void deleteAlertReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/price-alerts/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isNoContent());

        verify(priceAlertService).deleteAlert(5L, 1L);
    }

    @Test
    void getMyAlertsAcceptsNumericStringIdClaim() throws Exception {
        when(priceAlertService.getAlertsForUser(5L)).thenReturn(List.of(sampleDto(1L, 5L, "CLIENT")));

        mockMvc.perform(get("/price-alerts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", "5"))))
                .andExpect(status().isOk());

        verify(priceAlertService).getAlertsForUser(5L);
    }

    @Test
    void getMyAlertsRejectsNonNumericStringIdClaim() throws Exception {
        mockMvc.perform(get("/price-alerts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", "not-a-number"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyAlertsRejectsTokenWithoutIdClaim() throws Exception {
        mockMvc.perform(get("/price-alerts")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))))
                .andExpect(status().isUnauthorized());
    }

    private PriceAlertDto sampleDto(Long id, Long userId, String recipientType) {
        return new PriceAlertDto(id, userId, recipientType, 15L,
                PriceAlertCondition.ABOVE, new BigDecimal("200.0000"), "ALL", true,
                LocalDateTime.of(2026, 5, 18, 12, 0), null);
    }

    @TestConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
                    .build();
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test-user")
                    .build();
        }
    }
}
