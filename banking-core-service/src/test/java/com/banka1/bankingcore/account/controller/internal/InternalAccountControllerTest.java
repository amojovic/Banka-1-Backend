package com.banka1.bankingcore.account.controller.internal;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.domain.enums.Status;
import com.banka1.account_service.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WP-14 / WP-14b: standalone MockMvc testovi za {@link InternalAccountController}.
 *
 * <p>Kontroler je {@code permit-all} (ceo {@code /accounts/internal/**} prefiks),
 * pa standalone setup bez Spring Security-ja verno reprodukuje runtime ponasanje:
 * nema {@code @PreAuthorize}, samo path-prefix scoping. Testira se HTTP ugovor
 * (status kod + JSON telo) oba interna endpoint-a.
 */
@ExtendWith(MockitoExtension.class)
class InternalAccountControllerTest {

    @Mock
    private AccountRepository accountRepository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalAccountController(accountRepository)).build();
    }

    private Currency currency(CurrencyCode code) {
        return new Currency(code.name(), code, code.name(), Set.of("Zemlja"), "opis", Status.ACTIVE);
    }

    private Account account(Long id, Long ownerId, CurrencyCode code, String brojRacuna) {
        Account account = new TestAccount();
        account.setId(id);
        account.setVlasnik(ownerId);
        account.setCurrency(currency(code));
        account.setBrojRacuna(brojRacuna);
        return account;
    }

    /**
     * Minimal concrete {@link Account} subclass — {@code Account} je abstract sa
     * SINGLE_TABLE inheritance. {@code CheckingAccount}/{@code FxAccount}
     * override-uju {@code setCurrency} sa valutnom validacijom (RSD-only / ne-RSD),
     * pa se ne mogu koristiti za parametrizovanje po valuti. Isti obrazac kao
     * {@code InterbankReservationServiceTest.TestAccount}.
     */
    private static class TestAccount extends Account {
        // empty — koristi @Setter sa Account-a.
    }

    // -------------------- GET /accounts/internal/default/{ownerId} --------------------

    @Test
    void defaultAccount_returnsRsdAccountNumber() throws Exception {
        when(accountRepository.findByVlasnikAndCurrencyCode(7L, CurrencyCode.RSD))
                .thenReturn(Optional.of(account(701L, 7L, CurrencyCode.RSD, "111000110000000701")));

        mockMvc.perform(get("/accounts/internal/default/{ownerId}", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value("7"))
                .andExpect(jsonPath("$.accountNumber").value("111000110000000701"));
    }

    // -------------------- GET /accounts/internal/by-owner/{ownerId}/currency/{currencyCode} --------------------

    @Test
    void accountByOwnerAndCurrency_returnsAccountWhenPresent() throws Exception {
        when(accountRepository.findByVlasnikAndCurrencyCode(7L, CurrencyCode.USD))
                .thenReturn(Optional.of(account(801L, 7L, CurrencyCode.USD, "111000110000000801")));

        mockMvc.perform(get("/accounts/internal/by-owner/{ownerId}/currency/{currencyCode}", 7L, "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(801))
                .andExpect(jsonPath("$.accountNumber").value("111000110000000801"))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    void accountByOwnerAndCurrency_returnsRsdAccountWhenPresent() throws Exception {
        when(accountRepository.findByVlasnikAndCurrencyCode(7L, CurrencyCode.RSD))
                .thenReturn(Optional.of(account(701L, 7L, CurrencyCode.RSD, "111000110000000701")));

        mockMvc.perform(get("/accounts/internal/by-owner/{ownerId}/currency/{currencyCode}", 7L, "RSD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(701))
                .andExpect(jsonPath("$.currency").value("RSD"));
    }

    @Test
    void accountByOwnerAndCurrency_returns404WhenHolderHasNoAccountInThatCurrency() throws Exception {
        when(accountRepository.findByVlasnikAndCurrencyCode(7L, CurrencyCode.USD))
                .thenReturn(Optional.empty());

        // Odsustvo racuna u toj valuti je ocekivan, mapiran ishod (fallback grana),
        // ne greska — za razliku od defaultAccount koji baca 500.
        mockMvc.perform(get("/accounts/internal/by-owner/{ownerId}/currency/{currencyCode}", 7L, "USD"))
                .andExpect(status().isNotFound());
    }
}
