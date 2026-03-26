package com.banka1.account_service.controller;

import com.banka1.account_service.domain.Currency;
import com.banka1.account_service.domain.enums.CurrencyCode;
import com.banka1.account_service.service.CurrencyService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;


import java.util.List;

/**
 * REST kontroler za upravljanje valutama u Banka1 sistemu.
 * <p>
 * Omogucava korisnicima (CLIENT_BASIC i BASIC uloge) da preuzmu
 * informacije o dostupnim valutama u sistemu.
 * <p>
 * Valute se koriste pri kreiranju deviznih racuna.
 */
@RestController
@RequestMapping("/api/currencies")
@AllArgsConstructor
@PreAuthorize("hasAnyRole('CLIENT_BASIC','BASIC')")
public class CurrencyController {
    private CurrencyService currencyService;

    /**
     * Preuzima sve dostupne valute kao listu.
     *
     * @param jwt JWT token korisnika
     * @return lista svih {@link Currency} objekata
     */
    @GetMapping("/getAll")
    public ResponseEntity<List<Currency>> findAll(@AuthenticationPrincipal Jwt jwt){
            return new ResponseEntity<>(currencyService.findAll(), HttpStatus.OK);
    }

    /**
     * Preuzima sve dostupne valute sa mogucnoscu paginacije.
     *
     * @param jwt JWT token korisnika
     * @param page broj stranice (podrazumevana: 0)
     * @param size velicina stranice, max 100 (podrazumevana: 10)
     * @return {@link Page} sa {@link Currency} objektima
     */
    @GetMapping("/getAllPage")
    public ResponseEntity<Page<Currency>> findAllPage(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(defaultValue = "0") @Min(value = 0) int page,
                                                      @RequestParam(defaultValue = "10") @Min(value = 1) @Max(value = 100) int size){
        return new ResponseEntity<>(currencyService.findAllPage(page,size), HttpStatus.OK);
    }

    /**
     * Preuzima valutu po kodu (npr. USD, EUR, GBP).
     * <p>
     * Kod se prosledi kao query parametar i automatski se konvertuje u
     * {@link CurrencyCode} enum.
     *
     * @param jwt JWT token korisnika
     * @param code kod valute (napomena: se konvertuje u velika slova)
     * @return {@link Currency} objekat za zadati kod
     */
    @GetMapping()
    public ResponseEntity<Currency> findAllByCode(@AuthenticationPrincipal Jwt jwt,@RequestParam String code){
        return new ResponseEntity<>(currencyService.findByCode(CurrencyCode.valueOf(code.toUpperCase())), HttpStatus.OK);
    }

    /**
     * Preuzima valutu po kodu preko path parametra.
     *
     * @param jwt JWT token korisnika
     * @param code kod valute u path-u (napomena: se konvertuje u velika slova)
     * @return {@link Currency} objekat za zadati kod
     */
    @GetMapping("/{code}")
    public ResponseEntity<Currency> findByCode(@AuthenticationPrincipal Jwt jwt, @PathVariable String code) {
        return new ResponseEntity<>(currencyService.findByCode(CurrencyCode.valueOf(code.toUpperCase())), HttpStatus.OK);
    }

}
