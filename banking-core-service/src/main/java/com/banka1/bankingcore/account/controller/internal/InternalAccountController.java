package com.banka1.bankingcore.account.controller.internal;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoint-i koje SAGA orchestrator poziva za interne account operacije.
 * Koristi AccountRepository direktno — bez HTTP roundtrip-a ka samom sebi.
 */
@RestController
@RequestMapping("/accounts/internal")
@RequiredArgsConstructor
public class InternalAccountController {

    private final AccountRepository accountRepository;

    @GetMapping("/default/{ownerId}")
    public ResponseEntity<Map<String, String>> defaultAccount(@PathVariable Long ownerId) {
        Account account = accountRepository.findByVlasnikAndCurrencyCode(ownerId, CurrencyCode.RSD)
                .orElseThrow(() -> new IllegalStateException(
                        "Klijent " + ownerId + " nema RSD tekuci racun."));
        return ResponseEntity.ok(Map.of("ownerId", String.valueOf(ownerId), "accountNumber", account.getBrojRacuna()));
    }

    /**
     * WP-14b (Celina 3.7): razresava racun drzaoca u zadatoj valuti listinga.
     *
     * <p>Koristi {@code DividendAccountClient} pri isplati dividende — kvartalni
     * obracun prvo pokusava da kreditira drzaocev racun u valuti hartije (bez FX
     * konverzije), pa tek ako ga nema pada na RSD racun uz konverziju.
     *
     * <p>Vraca {@code 200} sa {@code {id, accountNumber, currency}} kada racun
     * postoji, ili {@code 404} kada drzalac nema racun u toj valuti — za razliku
     * od sibling {@link #defaultAccount} koji baca {@code 500} jer RSD racun
     * tretira kao obavezan. Ovde je odsustvo racuna ocekivan, mapiran ishod
     * (fallback grana), ne greska.
     *
     * @param ownerId      ID drzaoca (klijent ili aktuar)
     * @param currencyCode ISO 4217 kod valute listinga
     * @return {@code 200} sa detaljima racuna, ili {@code 404} ako racun ne postoji
     */
    @GetMapping("/by-owner/{ownerId}/currency/{currencyCode}")
    public ResponseEntity<Map<String, Object>> accountByOwnerAndCurrency(
            @PathVariable Long ownerId,
            @PathVariable CurrencyCode currencyCode) {
        return accountRepository.findByVlasnikAndCurrencyCode(ownerId, currencyCode)
                .<ResponseEntity<Map<String, Object>>>map(account -> ResponseEntity.ok(Map.of(
                        "id", account.getId(),
                        "accountNumber", account.getBrojRacuna(),
                        "currency", currencyCode.name())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
