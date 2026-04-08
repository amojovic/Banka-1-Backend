package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import com.banka1.stock_service.service.ListingQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Sort;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link ListingController}.
 */
@WebMvcTest(ListingController.class)
@AutoConfigureMockMvc
@Import(ListingControllerWebMvcTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class ListingControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListingMarketDataRefreshService listingMarketDataRefreshService;

    @MockitoBean
    private ListingQueryService listingQueryService;

    @Test
    void refreshListingReturnsForbiddenForBasicRole() throws Exception {
        mockMvc.perform(post("/api/listings/15/refresh")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(token -> token.claim("id", 12L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void refreshListingReturnsOkForSupervisorRole() throws Exception {
        when(listingMarketDataRefreshService.refreshListing(15L)).thenReturn(new ListingRefreshResponse(
                15L,
                "AAPL",
                ListingType.STOCK,
                LocalDate.of(2026, 4, 8),
                LocalDateTime.of(2026, 4, 8, 10, 15, 30)
        ));

        mockMvc.perform(post("/api/listings/15/refresh")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))
                                .jwt(token -> token.claim("id", 77L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(15))
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.listingType").value("STOCK"))
                .andExpect(jsonPath("$.dailySnapshotDate").value("2026-04-08"));

        verify(listingMarketDataRefreshService).refreshListing(15L);
    }

    @Test
    void getStockListingsReturnsOkForClientRole() throws Exception {
        when(listingQueryService.getStockListings(
                any(ListingFilterRequest.class),
                eq(0),
                eq(20),
                eq(ListingSortField.TICKER),
                eq(Sort.Direction.ASC)
        )).thenReturn(new PageImpl<>(List.of(
                new ListingSummaryResponse(
                        15L,
                        ListingType.STOCK,
                        "AAPL",
                        "Apple Inc.",
                        "XNAS",
                        new java.math.BigDecimal("180.00000000"),
                        new java.math.BigDecimal("1.50000000"),
                        1_000L,
                        new java.math.BigDecimal("99.00000000"),
                        null
                )
        )));

        mockMvc.perform(get("/api/listings/stocks")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$.content[0].exchangeMICCode").value("XNAS"))
                .andExpect(jsonPath("$.content[0].initialMarginCost").value(99.0));

        verify(listingQueryService).getStockListings(
                any(ListingFilterRequest.class),
                eq(0),
                eq(20),
                eq(ListingSortField.TICKER),
                eq(Sort.Direction.ASC)
        );
    }

    @Test
    void getForexListingsReturnsForbiddenForClientRole() throws Exception {
        mockMvc.perform(get("/api/listings/forex")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getForexListingsReturnsOkForBasicRole() throws Exception {
        when(listingQueryService.getForexListings(
                any(ListingFilterRequest.class),
                eq(0),
                eq(20),
                eq(ListingSortField.PRICE),
                eq(Sort.Direction.DESC)
        )).thenReturn(new PageImpl<>(List.of(
                new ListingSummaryResponse(
                        21L,
                        ListingType.FOREX,
                        "EUR/USD",
                        "EUR / USD",
                        "XNAS",
                        new java.math.BigDecimal("1.08350000"),
                        new java.math.BigDecimal("0.00000000"),
                        1_000L,
                        new java.math.BigDecimal("119.18500000"),
                        null
                )
        )));

        mockMvc.perform(get("/api/listings/forex")
                        .param("sortBy", "price")
                        .param("sortDirection", "desc")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(token -> token.claim("id", 44L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].ticker").value("EUR/USD"))
                .andExpect(jsonPath("$.content[0].listingType").value("FOREX"));

        verify(listingQueryService).getForexListings(
                any(ListingFilterRequest.class),
                eq(0),
                eq(20),
                eq(ListingSortField.PRICE),
                eq(Sort.Direction.DESC)
        );
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
