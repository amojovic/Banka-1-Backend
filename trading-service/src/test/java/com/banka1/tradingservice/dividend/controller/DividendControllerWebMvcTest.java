package com.banka1.tradingservice.dividend.controller;

import com.banka1.tradingservice.dividend.domain.DividendPayout;
import com.banka1.tradingservice.dividend.repository.DividendPayoutRepository;
import com.banka1.tradingservice.dividend.service.DividendDistributionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
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
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-14: WebMvc slice test za {@link DividendController} — skoupovanje na
 * {@code id} claim iz JWT-a, opcioni {@code listingId} filter, mapiranje
 * odgovora.
 */
@WebMvcTest(controllers = DividendController.class)
@AutoConfigureMockMvc
@Import({DividendController.class, DividendControllerWebMvcTest.TestSecurityConfig.class})
@ActiveProfiles("test")
class DividendControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DividendPayoutRepository payoutRepository;

    @MockitoBean
    private DividendDistributionService distributionService;

    private DividendPayout payout(Long userId, Long listingId) {
        return DividendPayout.builder()
                .id(1L)
                .userId(userId)
                .stockTicker("AAPL")
                .listingId(listingId)
                .quantity(10)
                .grossAmount(new BigDecimal("40.0000"))
                .currency("USD")
                .taxAmountRsd(new BigDecimal("351.0000"))
                .netAmount(new BigDecimal("34.0000"))
                .accountId(500L)
                .paymentDate(LocalDate.of(2026, 3, 31))
                .forBank(false)
                .build();
    }

    @Test
    void returnsCallersDividendHistory() throws Exception {
        when(payoutRepository.findByUserIdOrderByPaymentDateDesc(7L))
                .thenReturn(List.of(payout(7L, 1L)));

        mockMvc.perform(get("/dividends")
                        .with(jwt().jwt(j -> j.claim("id", 7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].stockTicker").value("AAPL"))
                .andExpect(jsonPath("$[0].grossAmount").value(40.0))
                .andExpect(jsonPath("$[0].taxAmountRsd").value(351.0))
                .andExpect(jsonPath("$[0].netAmount").value(34.0))
                .andExpect(jsonPath("$[0].forBank").value(false));

        verify(payoutRepository).findByUserIdOrderByPaymentDateDesc(7L);
        verify(payoutRepository, never()).findByUserIdAndListingIdOrderByPaymentDateDesc(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void filtersToOnePositionWhenListingIdGiven() throws Exception {
        when(payoutRepository.findByUserIdAndListingIdOrderByPaymentDateDesc(7L, 1L))
                .thenReturn(List.of(payout(7L, 1L)));

        mockMvc.perform(get("/dividends")
                        .param("listingId", "1")
                        .with(jwt().jwt(j -> j.claim("id", 7L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listingId").value(1));

        verify(payoutRepository).findByUserIdAndListingIdOrderByPaymentDateDesc(eq(7L), eq(1L));
        verify(payoutRepository, never()).findByUserIdOrderByPaymentDateDesc(
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void scopesToJwtIdClaim() throws Exception {
        when(payoutRepository.findByUserIdOrderByPaymentDateDesc(99L)).thenReturn(List.of());

        mockMvc.perform(get("/dividends")
                        .with(jwt().jwt(j -> j.claim("id", 99L))))
                .andExpect(status().isOk());

        verify(payoutRepository).findByUserIdOrderByPaymentDateDesc(99L);
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/dividends"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanTriggerPayout() throws Exception {
        when(distributionService.distribute(LocalDate.of(2026, 3, 31))).thenReturn(3);

        mockMvc.perform(post("/dividends/trigger")
                        .param("asOf", "2026-03-31")
                        .with(jwt().jwt(j -> j.claim("id", 1L))
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(3))
                .andExpect(jsonPath("$.asOf").value("2026-03-31"));

        verify(distributionService).distribute(LocalDate.of(2026, 3, 31));
    }

    @Test
    void nonAdminCannotTriggerPayout() throws Exception {
        mockMvc.perform(post("/dividends/trigger")
                        .with(jwt().jwt(j -> j.claim("id", 7L))
                                .authorities(new SimpleGrantedAuthority("ROLE_CLIENT"))))
                .andExpect(status().isForbidden());

        verify(distributionService, never()).distribute(any());
    }

    @Test
    void rejectsUnauthenticatedTrigger() throws Exception {
        mockMvc.perform(post("/dividends/trigger"))
                .andExpect(status().isUnauthorized());
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
