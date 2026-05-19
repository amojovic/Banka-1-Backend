package com.banka1.tradingservice.dividend.controller;

import com.banka1.tradingservice.dividend.dto.DividendPayoutDto;
import com.banka1.tradingservice.dividend.repository.DividendPayoutRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * WP-14 (Celina 3.7): read-only API za istoriju primljenih dividendi.
 *
 * <p>{@code GET /dividends} vraca isplate dividende ulogovanom korisniku,
 * najnovije prvo. Opcioni {@code listingId} parametar suzava na jednu poziciju
 * — portfolio strana ga koristi za prikaz istorije dividendi po hartiji.
 *
 * <p>Rezultat je uvek skoupovan na {@code id} claim iz JWT-a — korisnik vidi
 * iskljucivo sopstvene isplate.
 */
@RestController
@RequestMapping("/dividends")
@RequiredArgsConstructor
public class DividendController {

    private final DividendPayoutRepository payoutRepository;

    /**
     * Vraca istoriju dividendi ulogovanog korisnika.
     *
     * @param jwt       JWT ulogovanog korisnika ({@code id} claim = ID drzaoca)
     * @param listingId opcioni filter na jednu poziciju
     * @return lista {@link DividendPayoutDto}, najnovije prvo
     */
    @Operation(summary = "Get my dividend payout history")
    @GetMapping
    public ResponseEntity<List<DividendPayoutDto>> myDividends(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Long listingId) {

        Long userId = jwt.getClaim("id");
        List<DividendPayoutDto> result = (listingId == null
                ? payoutRepository.findByUserIdOrderByPaymentDateDesc(userId)
                : payoutRepository.findByUserIdAndListingIdOrderByPaymentDateDesc(userId, listingId))
                .stream()
                .map(DividendPayoutDto::from)
                .toList();
        return ResponseEntity.ok(result);
    }
}
