package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.AddWatchlistItemRequest;
import com.banka1.stock_service.dto.CreateWatchlistRequest;
import com.banka1.stock_service.dto.WatchlistDto;
import com.banka1.stock_service.dto.WatchlistItemDto;
import com.banka1.stock_service.service.WatchlistService;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WebMvc tests for {@link WatchlistController}.
 */
@WebMvcTest(WatchlistController.class)
@AutoConfigureMockMvc
@Import(WatchlistControllerWebMvcTest.TestSecurityConfig.class)
@ActiveProfiles("test")
class WatchlistControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WatchlistService watchlistService;

    @Test
    void getMyWatchlistsReturnsCallerWatchlists() throws Exception {
        when(watchlistService.getWatchlistsForUser(5L))
                .thenReturn(List.of(new WatchlistDto(1L, 5L, "Tech stocks", 3L,
                        LocalDateTime.of(2026, 5, 19, 12, 0))));

        mockMvc.perform(get("/watchlists")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].userId").value(5))
                .andExpect(jsonPath("$[0].name").value("Tech stocks"))
                .andExpect(jsonPath("$[0].itemCount").value(3));

        verify(watchlistService).getWatchlistsForUser(5L);
    }

    @Test
    void getMyWatchlistsRejectsUnauthenticatedCaller() throws Exception {
        mockMvc.perform(get("/watchlists"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createWatchlistReturnsCreatedWatchlist() throws Exception {
        when(watchlistService.createWatchlist(eq(5L), any(CreateWatchlistRequest.class)))
                .thenReturn(new WatchlistDto(7L, 5L, "Tech stocks", 0L,
                        LocalDateTime.of(2026, 5, 19, 12, 0)));

        mockMvc.perform(post("/watchlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Tech stocks\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.itemCount").value(0));

        verify(watchlistService).createWatchlist(eq(5L), any(CreateWatchlistRequest.class));
    }

    @Test
    void createWatchlistRejectsBlankName() throws Exception {
        mockMvc.perform(post("/watchlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createWatchlistAllowsActuaryRole() throws Exception {
        when(watchlistService.createWatchlist(eq(44L), any(CreateWatchlistRequest.class)))
                .thenReturn(new WatchlistDto(8L, 44L, "Forex pairs", 0L,
                        LocalDateTime.of(2026, 5, 19, 12, 0)));

        mockMvc.perform(post("/watchlists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Forex pairs\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_AGENT"))
                                .jwt(token -> token.claim("id", 44L))))
                .andExpect(status().isCreated());

        verify(watchlistService).createWatchlist(eq(44L), any(CreateWatchlistRequest.class));
    }

    @Test
    void deleteWatchlistReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/watchlists/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isNoContent());

        verify(watchlistService).deleteWatchlist(5L, 1L);
    }

    @Test
    void getWatchlistItemsReturnsEnrichedItems() throws Exception {
        when(watchlistService.getItems(eq(5L), eq(1L), isNull()))
                .thenReturn(List.of(sampleItem()));

        mockMvc.perform(get("/watchlists/1/items")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].ticker").value("AAPL"))
                .andExpect(jsonPath("$[0].price").value(212.40))
                .andExpect(jsonPath("$[0].volume").value(25000))
                .andExpect(jsonPath("$[0].listingType").value("STOCK"));

        verify(watchlistService).getItems(5L, 1L, null);
    }

    @Test
    void getWatchlistItemsPassesListingTypeFilter() throws Exception {
        when(watchlistService.getItems(5L, 1L, ListingType.FOREX)).thenReturn(List.of());

        mockMvc.perform(get("/watchlists/1/items")
                        .param("listingType", "forex")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isOk());

        verify(watchlistService).getItems(5L, 1L, ListingType.FOREX);
    }

    @Test
    void getWatchlistItemsRejectsUnknownListingTypeFilter() throws Exception {
        mockMvc.perform(get("/watchlists/1/items")
                        .param("listingType", "bond")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void addWatchlistItemReturnsCreatedItem() throws Exception {
        when(watchlistService.addItem(eq(5L), eq(1L), any(AddWatchlistItemRequest.class)))
                .thenReturn(sampleItem());

        mockMvc.perform(post("/watchlists/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"listingId\":15}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ticker").value("AAPL"));

        verify(watchlistService).addItem(eq(5L), eq(1L), any(AddWatchlistItemRequest.class));
    }

    @Test
    void addWatchlistItemRejectsMissingListingId() throws Exception {
        mockMvc.perform(post("/watchlists/1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void removeWatchlistItemReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/watchlists/1/items/10")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", 5L))))
                .andExpect(status().isNoContent());

        verify(watchlistService).removeItem(5L, 1L, 10L);
    }

    @Test
    void getMyWatchlistsAcceptsNumericStringIdClaim() throws Exception {
        when(watchlistService.getWatchlistsForUser(5L)).thenReturn(List.of());

        mockMvc.perform(get("/watchlists")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", "5"))))
                .andExpect(status().isOk());

        verify(watchlistService).getWatchlistsForUser(5L);
    }

    @Test
    void getMyWatchlistsRejectsNonNumericStringIdClaim() throws Exception {
        mockMvc.perform(get("/watchlists")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(token -> token.claim("id", "not-a-number"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getMyWatchlistsRejectsTokenWithoutIdClaim() throws Exception {
        mockMvc.perform(get("/watchlists")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))))
                .andExpect(status().isUnauthorized());
    }

    private WatchlistItemDto sampleItem() {
        return new WatchlistItemDto(10L, 1L, 15L, "AAPL", "Apple Inc.",
                new BigDecimal("212.40"), new BigDecimal("4.60"), 25_000L, ListingType.STOCK,
                LocalDateTime.of(2026, 5, 19, 12, 0));
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
