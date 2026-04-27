package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.StockBulkRefreshAcceptedResponse;
import com.banka1.stock_service.dto.StockMarketDataRefreshResponse;
import com.banka1.stock_service.service.StockMarketDataRefreshService;
import com.banka1.stock_service.web.StockServiceResponseStatusExceptionHandler;
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

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link StockMarketDataRefreshAdminController}.
 */
@WebMvcTest(StockMarketDataRefreshAdminController.class)
@AutoConfigureMockMvc
@Import({
        StockMarketDataRefreshAdminControllerWebMvcTest.TestSecurityConfig.class,
        StockServiceResponseStatusExceptionHandler.class
})
@ActiveProfiles("test")
class StockMarketDataRefreshAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StockMarketDataRefreshService stockMarketDataRefreshService;

    @Test
    void refreshStockMarketDataReturnsOkForAuthenticatedCaller() throws Exception {
        when(stockMarketDataRefreshService.refreshStock("AAPL")).thenReturn(new StockMarketDataRefreshResponse(
                "AAPL",
                1L,
                10L,
                2,
                LocalDateTime.of(2026, 4, 8, 10, 15, 30)
        ));

        mockMvc.perform(post("/admin/stocks/AAPL/refresh-market-data")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPERVISOR"))
                                .jwt(token -> token.claim("id", 77L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticker").value("AAPL"))
                .andExpect(jsonPath("$.refreshedDailyEntries").value(2));

        verify(stockMarketDataRefreshService).refreshStock("AAPL");
    }

    @Test
    void refreshAllStocksReturnsAcceptedAndTriggersBackgroundJob() throws Exception {
        mockMvc.perform(post("/admin/stocks/refresh-all")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(token -> token.claim("id", 77L))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("STARTED"))
                .andExpect(jsonPath("$.message").value("Bulk stock refresh started."));

        verify(stockMarketDataRefreshService).triggerRefreshAllStocks();
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
