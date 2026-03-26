package com.banka1.account_service.service;

import com.banka1.account_service.dto.request.PaymentDto;

import com.banka1.account_service.dto.response.InfoResponseDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Servis za izvrsavanje internih transakcija i transfera izmedju racuna.
 * Pozivaju ga interni servisi putem {@code /internal/accounts} endpointa.
 */
public interface AccountService {

    /**
     * Izvrsava transakciju izmedju racuna razlicitih vlasnika.
     * Validira oba racuna i banka-racune za svaku valutu, zatim prenosi sredstva.
     *
     * @param paymentDto podaci o placanju (brojevi racuna, iznosi, provizija, ID klijenta)
     * @return azurirana stanja oba racuna nakon transakcije
     */
    UpdatedBalanceResponseDto transaction(PaymentDto paymentDto);

    /**
     * Izvrsava transfer izmedju dva racuna istog vlasnika.
     * Razlikuje se od {@link #transaction} po tome sto proverava da oba racuna
     * pripadaju istom vlasniku.
     *
     * @param paymentDto podaci o transferu (brojevi racuna, iznosi, provizija, ID klijenta)
     * @return azurirana stanja oba racuna nakon transfera
     */
    UpdatedBalanceResponseDto transfer(PaymentDto paymentDto);

    /**
     * Vraca informacije o valutama i vlasnicima dva racuna.
     * Koristi se od strane transaction-service-a za proveru pre izvrsavanja transakcije.
     *
     * @param jwt JWT token pozivaoca
     * @param fromAccountNumber broj racuna posiljaoca
     * @param toAccountNumber broj racuna primaoca
     * @return DTO sa valutama i ID-evima vlasnika oba racuna
     */
    InfoResponseDto info(Jwt jwt, String fromAccountNumber, String toAccountNumber);
}
