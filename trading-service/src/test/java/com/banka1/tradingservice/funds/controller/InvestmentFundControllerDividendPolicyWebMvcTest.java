package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.service.FundLiquidationService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-17 (Celina 4.3): WebMvc slice test za {@code PATCH /funds/{id}/dividend-policy} —
 * supervizor-gating ({@code FUND_AGENT_MANAGE}), validacija tela, mapiranje odgovora.
 */
@WebMvcTest(controllers = InvestmentFundController.class)
@AutoConfigureMockMvc
@Import({InvestmentFundController.class,
        InvestmentFundControllerDividendPolicyWebMvcTest.TestSecurityConfig.class})
@ActiveProfiles("test")
class InvestmentFundControllerDividendPolicyWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvestmentFundService fundService;

    @MockitoBean
    private FundLiquidationService fundLiquidationService;

    private InvestmentFundDto fundDto(FundDividendPolicy policy) {
        return InvestmentFundDto.builder()
                .id(1L)
                .naziv("Alpha Growth")
                .minimumContribution(new BigDecimal("1000.00"))
                .managerId(50L)
                .likvidnaSredstva(new BigDecimal("100000.00"))
                .accountNumber("1111111111111111")
                .totalValue(new BigDecimal("100000.00"))
                .profit(BigDecimal.ZERO)
                .dividendPolicy(policy)
                .build();
    }

    @Test
    void supervisorCanSwitchPolicyToDistribute() throws Exception {
        when(fundService.updateDividendPolicy(1L, FundDividendPolicy.DISTRIBUTE))
                .thenReturn(fundDto(FundDividendPolicy.DISTRIBUTE));

        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dividendPolicy\":\"DISTRIBUTE\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("FUND_AGENT_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.dividendPolicy").value("DISTRIBUTE"));

        verify(fundService).updateDividendPolicy(eq(1L), eq(FundDividendPolicy.DISTRIBUTE));
    }

    @Test
    void supervisorCanSwitchPolicyToReinvest() throws Exception {
        when(fundService.updateDividendPolicy(1L, FundDividendPolicy.REINVEST))
                .thenReturn(fundDto(FundDividendPolicy.REINVEST));

        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dividendPolicy\":\"REINVEST\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("FUND_AGENT_MANAGE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dividendPolicy").value("REINVEST"));
    }

    @Test
    void forbidsCallerWithoutFundAgentManageAuthority() throws Exception {
        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dividendPolicy\":\"DISTRIBUTE\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("CLIENT_TRADING"))))
                .andExpect(status().isForbidden());

        verify(fundService, never()).updateDividendPolicy(any(), any());
    }

    @Test
    void forbidsUnauthenticated() throws Exception {
        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dividendPolicy\":\"DISTRIBUTE\"}"))
                .andExpect(status().isUnauthorized());

        verify(fundService, never()).updateDividendPolicy(any(), any());
    }

    @Test
    void rejectsMissingPolicyInBody() throws Exception {
        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("FUND_AGENT_MANAGE"))))
                .andExpect(status().isBadRequest());

        verify(fundService, never()).updateDividendPolicy(any(), any());
    }

    @Test
    void rejectsUnknownPolicyValue() throws Exception {
        mockMvc.perform(patch("/funds/1/dividend-policy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dividendPolicy\":\"BOGUS\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("FUND_AGENT_MANAGE"))))
                .andExpect(status().isBadRequest());

        verify(fundService, never()).updateDividendPolicy(any(), any());
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
