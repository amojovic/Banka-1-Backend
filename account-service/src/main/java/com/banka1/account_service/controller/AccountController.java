package com.banka1.account_service.controller;

import com.banka1.account_service.dto.request.PaymentDto;
import com.banka1.account_service.dto.response.InfoResponseDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import com.banka1.account_service.service.AccountService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/internal/accounts")
@PreAuthorize("hasRole('SERVICE')")
public class AccountController {

    private AccountService accountService;

    /**
     * REST kontroler za interne operacije nad racunima u Banka1 sistemu.
     * <p>
     * Ovi endpointi su namenjeni samo inter-servisnoj komunikaciji (npr. od Card servisa
     * ili Payment servisa) i zahtevaju SERVICE ulogu.
     * <p>
     * Omogucava:
     * <ul>
     *   <li>Obradu finansijskih transakcija (transfer novca, transakcije)</li>
     *   <li>Preuzimanje informacija o racunima radi verifikacije</li>
     * </ul>
     */

    /**
     * Obradi finansijsku transakciju na racunu (debit/credit operacija).
     * <p>
     * Transakcija se vrsi preko DTO-a koji sadrzi sve potrebne informacije
     * (racun, iznos, tip operacije, itd.).
     *
     * @param jwt JWT token servisa koji prave zahtev
     * @param paymentDto podaci o transakciji
     * @return {@link UpdatedBalanceResponseDto} sa azuriranim stanjem racuna
     */
    @PostMapping("/transaction")
    public ResponseEntity<UpdatedBalanceResponseDto> transaction(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PaymentDto paymentDto) {
        return new ResponseEntity<>(accountService.transaction(paymentDto),HttpStatus.OK);
    }

    /**
     * Obradi transfer novca izmedju dva racuna.
     * <p>
     * Ova operacija je slozenija od obicne transakcije jer ukljucuje
     * ažuriranje dva racuna (izvor i odrediste).
     *
     * @param jwt JWT token servisa koji prave zahtev
     * @param paymentDto podaci o transferu (mora sadrzati i izvor i odrediste)
     * @return {@link UpdatedBalanceResponseDto} sa azuriranim stanjem
     */
    @PostMapping("/transfer")
    public ResponseEntity<UpdatedBalanceResponseDto> transfer(@AuthenticationPrincipal Jwt jwt, @RequestBody @Valid PaymentDto paymentDto) {
        return new ResponseEntity<>(accountService.transfer(paymentDto),HttpStatus.OK);
    }

    /**
     * Preuzima informacije o dva racuna za verifikaciju ili debugging.
     * <p>
     * Koristi se obicno pre izvršavanja transakcije da se proveri
     * валидност racuna i dostupnih sredstava.
     *
     * @param jwt JWT token servisa
     * @param fromBankNumber broj izvornog racuna
     * @param toBankNumber broj odredisnog racuna
     * @return {@link InfoResponseDto} sa informacijama o oba racuna
     */
    @GetMapping("/info")
    public ResponseEntity<InfoResponseDto> info(@AuthenticationPrincipal Jwt jwt,@RequestParam String fromBankNumber,@RequestParam String toBankNumber)
    {
        return new ResponseEntity<>(accountService.info(jwt,fromBankNumber,toBankNumber),HttpStatus.OK);
    }

}
