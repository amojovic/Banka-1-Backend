package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.dto.FundStatisticsDto;
import com.banka1.tradingservice.funds.dto.FundValueSnapshotDto;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.service.FundLiquidationService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import com.banka1.tradingservice.funds.service.InvestmentFundService.FundSortField;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-18 (Celina 4.4): WebMvc slice test za nove fund-statistics endpoint-e —
 * sortabilni discovery, {@code GET /funds/{id}/statistics},
 * {@code GET /funds/{id}/value-history}, {@code GET /funds/value-history/average}.
 *
 * <p>Ti endpoint-i su citljivi svakome ko vidi fondove (bez {@code @PreAuthorize}),
 * pa testovi koriste anoniman zahtev gde je to namera.
 */
@WebMvcTest(controllers = InvestmentFundController.class)
@AutoConfigureMockMvc
@Import({InvestmentFundController.class,
        InvestmentFundControllerStatisticsWebMvcTest.TestSecurityConfig.class})
@ActiveProfiles("test")
class InvestmentFundControllerStatisticsWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InvestmentFundService fundService;

    @MockitoBean
    private FundLiquidationService fundLiquidationService;

    // ----------------------- discovery sorting -----------------------

    @Test
    void discovery_withoutParams_usesNazivAscending() throws Exception {
        when(fundService.discovery(FundSortField.NAZIV, true)).thenReturn(List.of());

        mockMvc.perform(get("/funds")).andExpect(status().isOk());

        verify(fundService).discovery(eq(FundSortField.NAZIV), eq(true));
    }

    @Test
    void discovery_sortByVolatilityDesc_passesThroughToService() throws Exception {
        when(fundService.discovery(FundSortField.VOLATILITY, false)).thenReturn(List.of(
                InvestmentFundDto.builder().id(1L).naziv("A")
                        .volatility(new BigDecimal("0.30")).build()));

        mockMvc.perform(get("/funds").param("sort", "volatility").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].volatility").value(0.30));

        verify(fundService).discovery(eq(FundSortField.VOLATILITY), eq(false));
    }

    @Test
    void discovery_sortByAnnualizedReturn_mapsParamToEnum() throws Exception {
        when(fundService.discovery(FundSortField.ANNUALIZED_RETURN, true)).thenReturn(List.of());

        mockMvc.perform(get("/funds").param("sort", "annualizedReturn"))
                .andExpect(status().isOk());

        verify(fundService).discovery(eq(FundSortField.ANNUALIZED_RETURN), eq(true));
    }

    @Test
    void discovery_unknownSortParam_fallsBackToNaziv() throws Exception {
        when(fundService.discovery(FundSortField.NAZIV, true)).thenReturn(List.of());

        mockMvc.perform(get("/funds").param("sort", "bogus-field"))
                .andExpect(status().isOk());

        verify(fundService).discovery(eq(FundSortField.NAZIV), eq(true));
    }

    // ----------------------- statistics endpoint -----------------------

    @Test
    void statistics_returnsMetricsWhenAvailable() throws Exception {
        when(fundService.fundStatistics(1L)).thenReturn(FundStatisticsDto.builder()
                .fundId(1L)
                .metricsAvailable(true)
                .snapshotCount(4)
                .minSnapshotsRequired(3)
                .annualizedReturn(new BigDecimal("2.32150625"))
                .volatility(new BigDecimal("0.37749172"))
                .maxDrawdown(new BigDecimal("0.25000000"))
                .rewardToVariabilityRatio(new BigDecimal("0.39735971"))
                .build());

        mockMvc.perform(get("/funds/1/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fundId").value(1))
                .andExpect(jsonPath("$.metricsAvailable").value(true))
                .andExpect(jsonPath("$.volatility").value(0.37749172))
                .andExpect(jsonPath("$.maxDrawdown").value(0.25000000));
    }

    @Test
    void statistics_returnsUnavailableWhenBelowMinimum() throws Exception {
        when(fundService.fundStatistics(2L)).thenReturn(FundStatisticsDto.builder()
                .fundId(2L)
                .metricsAvailable(false)
                .snapshotCount(1)
                .minSnapshotsRequired(3)
                .build());

        mockMvc.perform(get("/funds/2/statistics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metricsAvailable").value(false))
                .andExpect(jsonPath("$.volatility").doesNotExist());
    }

    @Test
    void statistics_isReadableAnonymously() throws Exception {
        when(fundService.fundStatistics(3L)).thenReturn(FundStatisticsDto.builder()
                .fundId(3L).metricsAvailable(false).build());

        // no jwt() post-processor -> anonymous; statistics has no @PreAuthorize
        mockMvc.perform(get("/funds/3/statistics")).andExpect(status().isOk());
    }

    // ----------------------- value-history endpoints -----------------------

    @Test
    void valueHistory_returnsSnapshotSeries() throws Exception {
        when(fundService.fundValueHistory(1L)).thenReturn(List.of(
                FundValueSnapshotDto.builder().fundId(1L)
                        .snapshotDate(LocalDate.of(2026, 1, 1))
                        .totalValue(new BigDecimal("100000.0000"))
                        .profit(BigDecimal.ZERO).build(),
                FundValueSnapshotDto.builder().fundId(1L)
                        .snapshotDate(LocalDate.of(2026, 2, 1))
                        .totalValue(new BigDecimal("120000.0000"))
                        .profit(new BigDecimal("20000.0000")).build()));

        mockMvc.perform(get("/funds/1/value-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].snapshotDate").value("2026-01-01"))
                .andExpect(jsonPath("$[1].totalValue").value(120000.0000));
    }

    @Test
    void averageValueHistory_returnsSystemAverageSeries() throws Exception {
        when(fundService.averageValueHistory()).thenReturn(List.of(
                FundValueSnapshotDto.builder().fundId(null)
                        .snapshotDate(LocalDate.of(2026, 1, 1))
                        .totalValue(new BigDecimal("150000.0000"))
                        .profit(new BigDecimal("20000.0000")).build()));

        mockMvc.perform(get("/funds/value-history/average"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fundId").doesNotExist())
                .andExpect(jsonPath("$[0].totalValue").value(150000.0000));

        verify(fundService).averageValueHistory();
    }

    @TestConfiguration
    @EnableMethodSecurity
    @EnableWebSecurity
    static class TestSecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    // discovery/statistics/value-history are public-readable (no @PreAuthorize)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
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
