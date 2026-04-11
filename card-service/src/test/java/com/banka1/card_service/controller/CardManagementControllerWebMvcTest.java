package com.banka1.card_service.controller;

import com.banka1.card_service.advice.GlobalExceptionHandler;
import com.banka1.card_service.domain.Card;
import com.banka1.card_service.domain.enums.CardStatus;
import com.banka1.card_service.domain.enums.CardType;
import com.banka1.card_service.dto.card_management.request.UpdateCardLimitDTO;
import com.banka1.card_service.dto.card_management.response.CardDetailDTO;
import com.banka1.card_service.dto.card_management.response.CardInternalSummaryDTO;
import com.banka1.card_service.dto.card_management.response.CardSummaryDTO;
import com.banka1.card_service.service.CardLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CardManagementController.class)
@AutoConfigureMockMvc
@Import({GlobalExceptionHandler.class, CardControllerSupport.class})
@ActiveProfiles("test")
class CardManagementControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private CardLifecycleService cardLifecycleService;

    @Test
    void getCardsForClientReturnsMaskedCardsForOwner() throws Exception {
        when(cardLifecycleService.getCardsForClient(1L)).thenReturn(List.of(new CardSummaryDTO(card())));

        mockMvc.perform(get("/client/1")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 1L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedCardNumber").value("5798********5571"));
    }

    @Test
    void getCardsForClientAllowsEmployeeForAnyClient() throws Exception {
        when(cardLifecycleService.getCardsForClient(2L)).thenReturn(List.of(new CardSummaryDTO(card())));

        mockMvc.perform(get("/client/2")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 100L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedCardNumber").value("5798********5571"));

        verify(cardLifecycleService).getCardsForClient(2L);
    }

    @Test
    void getCardsForClientReturnsForbiddenForDifferentClient() throws Exception {
        mockMvc.perform(get("/client/2")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 1L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ERR_CARD_007"));

        verify(cardLifecycleService, never()).getCardsForClient(2L);
    }

    @Test
    void getCardDetailsReturnsForbiddenForDifferentClient() throws Exception {
        when(cardLifecycleService.getClientIdByCardNumber("5798123456785571")).thenReturn(2L);

        mockMvc.perform(get("/5798123456785571")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 1L))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ERR_CARD_007"));

        verify(cardLifecycleService, never()).getCardByCardNumber(any());
    }

    @Test
    void getCardDetailsAllowsEmployeeOnSharedRoute() throws Exception {
        when(cardLifecycleService.getCardByCardNumber("5798123456785571"))
                .thenReturn(new CardDetailDTO(card()));

        mockMvc.perform(get("/5798123456785571")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 100L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cardNumber").value("5798123456785571"));

        verify(cardLifecycleService, never()).getClientIdByCardNumber(any());
    }

    @Test
    void getCardsByAccountUsesEmployeeRoute() throws Exception {
        when(cardLifecycleService.getCardsByAccountNumber("265000000000123456"))
                .thenReturn(List.of(new CardSummaryDTO(card())));

        mockMvc.perform(get("/account/265000000000123456")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 100L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].maskedCardNumber").value("5798********5571"));
    }

    @Test
    void getCardsByAccountInternalReturnsInternalSummaries() throws Exception {
        when(cardLifecycleService.getInternalCardsByAccountNumber("265000000000123456"))
                .thenReturn(List.of(new CardInternalSummaryDTO(card())));

        mockMvc.perform(get("/internal/account/265000000000123456")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cardNumber").value("5798********5571"))
                .andExpect(jsonPath("$[0].cardType").value("Visa Debit"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].expiryDate").value("2031-03-20"))
                .andExpect(jsonPath("$[0].accountNumber").value("265000000000123456"));
    }

    @Test
    void blockCardAllowsEmployeeOnSharedRoute() throws Exception {
        mockMvc.perform(put("/5798123456785571/block")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 100L))))
                .andExpect(status().isOk());

        verify(cardLifecycleService).blockCard("5798123456785571");
        verify(cardLifecycleService, never()).getClientIdByCardNumber(any());
    }

    @Test
    void updateCardLimitChecksClientOwnership() throws Exception {
        UpdateCardLimitDTO request = new UpdateCardLimitDTO();
        request.setCardLimit(BigDecimal.valueOf(2500));

        when(cardLifecycleService.getClientIdByCardNumber("5798123456785571")).thenReturn(1L);

        mockMvc.perform(put("/5798123456785571/limit")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CLIENT_BASIC"))
                                .jwt(jwt -> jwt.claim("id", 1L)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(cardLifecycleService).updateCardLimit("5798123456785571", BigDecimal.valueOf(2500));
    }

    private Card card() {
        Card card = new Card();
        card.setCardNumber("5798123456785571");
        card.setCardType(CardType.DEBIT);
        card.setCardName("Visa Debit");
        card.setCreationDate(LocalDate.of(2026, 3, 20));
        card.setExpirationDate(LocalDate.of(2031, 3, 20));
        card.setAccountNumber("265000000000123456");
        card.setCardLimit(BigDecimal.valueOf(1000));
        card.setStatus(CardStatus.ACTIVE);
        card.setClientId(1L);
        return card;
    }
}
