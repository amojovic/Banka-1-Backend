package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.domain.StockExchange;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
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
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link StockDividendInternalController} (WP-14).
 */
@WebMvcTest(StockDividendInternalController.class)
@AutoConfigureMockMvc
@Import(StockDividendInternalControllerWebMvcTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class StockDividendInternalControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListingRepository listingRepository;

    @MockitoBean
    private StockRepository stockRepository;

    private Stock stock(String ticker, BigDecimal yield) {
        Stock s = new Stock();
        s.setTicker(ticker);
        s.setName(ticker + " Inc.");
        s.setOutstandingShares(1_000L);
        s.setDividendYield(yield);
        return s;
    }

    private Listing listing(Long id, String ticker, BigDecimal price, String currency) {
        StockExchange exchange = new StockExchange();
        exchange.setCurrency(currency);
        Listing l = new Listing();
        l.setId(id);
        l.setSecurityId(id);
        l.setListingType(ListingType.STOCK);
        l.setTicker(ticker);
        l.setName(ticker + " Inc.");
        l.setPrice(price);
        l.setStockExchange(exchange);
        return l;
    }

    @Test
    void returnsDividendDataForServiceRole() throws Exception {
        when(stockRepository.findAll()).thenReturn(List.of(
                stock("AAPL", new BigDecimal("0.0044")),
                stock("MSFT", new BigDecimal("0.0068"))));
        when(listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.STOCK))
                .thenReturn(List.of(
                        listing(15L, "AAPL", new BigDecimal("180.00"), "USD"),
                        listing(16L, "MSFT", new BigDecimal("420.00"), "USD")));

        mockMvc.perform(get("/stocks/internal/dividend-data")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].listingId").value(15))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].price").value(180.00))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].dividendYield").value(0.0044))
                .andExpect(jsonPath("$[1].ticker").value("MSFT"))
                .andExpect(jsonPath("$[1].dividendYield").value(0.0068));
    }

    @Test
    void skipsListingsWithoutMatchingStock() throws Exception {
        when(stockRepository.findAll()).thenReturn(List.of(stock("AAPL", new BigDecimal("0.0044"))));
        when(listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.STOCK))
                .thenReturn(List.of(
                        listing(15L, "AAPL", new BigDecimal("180.00"), "USD"),
                        listing(99L, "ORPHAN", new BigDecimal("10.00"), "USD")));

        mockMvc.perform(get("/stocks/internal/dividend-data")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"));
    }

    @Test
    void matchesStockToListingCaseInsensitively() throws Exception {
        when(stockRepository.findAll()).thenReturn(List.of(stock("aapl", new BigDecimal("0.0044"))));
        when(listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.STOCK))
                .thenReturn(List.of(listing(15L, "AAPL", new BigDecimal("180.00"), "USD")));

        mockMvc.perform(get("/stocks/internal/dividend-data")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].dividendYield").value(0.0044));
    }

    @Test
    void forbidsNonServiceRole() throws Exception {
        mockMvc.perform(get("/stocks/internal/dividend-data")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/stocks/internal/dividend-data"))
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
