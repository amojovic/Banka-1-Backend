package com.banka1.tradingservice.otc.controller;

import com.banka1.tradingservice.otc.domain.OtcOfferStatus;
import com.banka1.tradingservice.otc.domain.OtcRevisionAction;
import com.banka1.tradingservice.otc.dto.OtcOfferDto;
import com.banka1.tradingservice.otc.dto.OtcOfferRevisionDto;
import com.banka1.tradingservice.otc.exception.OtcExceptionHandler;
import com.banka1.tradingservice.otc.service.OtcService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-16 (Celina 4.2): WebMvc slice test za istorijske endpointe {@link OtcController}-a —
 * {@code GET /otc/offers/history} i {@code GET /otc/offers/{id}/history}: parsiranje
 * filtera, prosledjivanje JWT {@code id} claim-a i strukturu odgovora.
 */
@WebMvcTest(controllers = OtcController.class)
@AutoConfigureMockMvc
@Import({OtcController.class, OtcExceptionHandler.class, OtcControllerHistoryWebMvcTest.TestSecurityConfig.class})
@ActiveProfiles("test")
class OtcControllerHistoryWebMvcTest {

    private static final long CALLER_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OtcService otcService;

    private OtcOfferDto sampleOffer() {
        return OtcOfferDto.builder()
                .id(1L)
                .stockTicker("AAPL")
                .buyerId(CALLER_ID)
                .sellerId(200L)
                .amount(10)
                .pricePerStock(new BigDecimal("150.00"))
                .premium(new BigDecimal("400.00"))
                .settlementDate(LocalDate.of(2026, 8, 1))
                .status(OtcOfferStatus.ACCEPTED)
                .modifiedBy("user#200")
                .lastModified(LocalDateTime.of(2026, 5, 14, 9, 0))
                .build();
    }

    private OtcOfferRevisionDto sampleRevision() {
        return OtcOfferRevisionDto.builder()
                .id(5L)
                .offerId(1L)
                .action(OtcRevisionAction.CREATE)
                .actorUserId(CALLER_ID)
                .actorName("Marko Markovic")
                .actorRole("BUYER")
                .newAmount(10)
                .newPricePerStock(new BigDecimal("150.00"))
                .newPremium(new BigDecimal("400.00"))
                .newSettlementDate(LocalDate.of(2026, 8, 1))
                .createdAt(LocalDateTime.of(2026, 5, 10, 9, 0))
                .build();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor callerJwt() {
        return jwt().jwt(j -> j.claim("id", CALLER_ID).claim("name", "Marko Markovic"));
    }

    @Test
    void historyReturnsOffersForCaller() throws Exception {
        when(otcService.historyForUser(eq(CALLER_ID), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(List.of(sampleOffer()));

        mockMvc.perform(get("/otc/offers/history").with(callerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
    }

    @Test
    void historyForbidsUnauthenticated() throws Exception {
        mockMvc.perform(get("/otc/offers/history"))
                .andExpect(status().isUnauthorized());

        verify(otcService, never()).historyForUser(any(), any(), any(), any(), any());
    }

    @Test
    void historyParsesStatusAndCounterpartyFilters() throws Exception {
        when(otcService.historyForUser(eq(CALLER_ID), eq(OtcOfferStatus.REJECTED),
                isNull(), isNull(), eq("200")))
                .thenReturn(List.of());

        mockMvc.perform(get("/otc/offers/history")
                        .param("status", "REJECTED")
                        .param("counterparty", "200")
                        .with(callerJwt()))
                .andExpect(status().isOk());

        verify(otcService).historyForUser(eq(CALLER_ID), eq(OtcOfferStatus.REJECTED),
                isNull(), isNull(), eq("200"));
    }

    @Test
    void historyParsesBareDateRangeBounds() throws Exception {
        when(otcService.historyForUser(eq(CALLER_ID), isNull(),
                eq(LocalDateTime.of(2026, 5, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 5, 31, 23, 59, 59).withNano(999_999_999)),
                isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/otc/offers/history")
                        .param("from", "2026-05-01")
                        .param("to", "2026-05-31")
                        .with(callerJwt()))
                .andExpect(status().isOk());

        verify(otcService).historyForUser(eq(CALLER_ID), isNull(),
                eq(LocalDateTime.of(2026, 5, 1, 0, 0)),
                eq(LocalDateTime.of(2026, 5, 31, 23, 59, 59).withNano(999_999_999)),
                isNull());
    }

    @Test
    void historyRejectsUnknownStatus() throws Exception {
        mockMvc.perform(get("/otc/offers/history")
                        .param("status", "BOGUS")
                        .with(callerJwt()))
                .andExpect(status().isBadRequest());

        verify(otcService, never()).historyForUser(any(), any(), any(), any(), any());
    }

    @Test
    void historyRejectsInvalidDate() throws Exception {
        mockMvc.perform(get("/otc/offers/history")
                        .param("from", "not-a-date")
                        .with(callerJwt()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void offerRevisionHistoryReturnsTrail() throws Exception {
        when(otcService.revisionTrail(1L, CALLER_ID)).thenReturn(List.of(sampleRevision()));

        mockMvc.perform(get("/otc/offers/1/history").with(callerJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("CREATE"))
                .andExpect(jsonPath("$[0].actorRole").value("BUYER"))
                .andExpect(jsonPath("$[0].newAmount").value(10));
    }

    @Test
    void offerRevisionHistoryNotFoundForNonParticipant() throws Exception {
        // Servis baca IllegalArgumentException za ne-ucesnika; OtcExceptionHandler -> 404.
        when(otcService.revisionTrail(99L, CALLER_ID))
                .thenThrow(new IllegalArgumentException("OTC ponuda 99 ne postoji."));

        mockMvc.perform(get("/otc/offers/99/history").with(callerJwt()))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration
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
