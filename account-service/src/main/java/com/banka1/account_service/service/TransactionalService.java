package com.banka1.account_service.service;

import com.banka1.account_service.domain.Account;
import com.banka1.account_service.dto.request.BankPaymentDto;
import com.banka1.account_service.dto.request.PaymentDto;
import com.banka1.account_service.dto.response.UpdatedBalanceResponseDto;
import org.springframework.security.oauth2.jwt.Jwt;

import java.math.BigDecimal;

/**
 * Servis koji izvrsava atomicne debitne/kreditne operacije nad racunima u okviru jedne transakcije.
 * Koristi se iskljucivo interno od strane {@link AccountService} implementacije.
 */
public interface TransactionalService {

    /**
     * Atomicno prenosi sredstva sa jednog racuna na drugi, ukljucujuci proviziju banke.
     * <p>
     * Tok operacije:
     * <ol>
     *   <li>Debita {@code from} racun za {@code paymentDto.fromAmount}</li>
     *   <li>Kreditira {@code to} racun za {@code paymentDto.toAmount}</li>
     *   <li>Kreditira {@code bankSender} racun za proviziju</li>
     * </ol>
     *
     * @param from       racun sa kojeg se skidaju sredstva
     * @param to         racun na koji se uplacuju sredstva
     * @param bankSender banka-racun u valuti posiljaoca (prima proviziju)
     * @param bankTarget banka-racun u valuti primaoca
     * @param paymentDto podaci o placanju (iznosi, provizija)
     * @return azurirana stanja oba klijentska racuna
     */
    UpdatedBalanceResponseDto transfer(Account from, Account to, Account bankSender, Account bankTarget, PaymentDto paymentDto);


    void transfer(Account sender, Account recipient, BankPaymentDto paymentDto);


    void creditTransactional(Account account, BigDecimal amount);


    void debitTransactional(Account account, BigDecimal amount);


    /**
     * Single-side debit on a single account, used to settle the funds leg of a
     * BUY exchange order. Reuses the same balance/limit primitives as
     * {@link #debitTransactional(Account, BigDecimal)}; no counterparty leg is
     * generated.
     *
     * @param account account to debit
     * @param amount  positive amount to debit
     */
    void withdrawOneSided(Account account, BigDecimal amount);


    /**
     * Single-side credit on a single account, used to settle the funds leg of a
     * SELL exchange order. Symmetric counterpart to
     * {@link #withdrawOneSided(Account, BigDecimal)}.
     *
     * @param account account to credit
     * @param amount  positive amount to credit
     */
    void depositOneSided(Account account, BigDecimal amount);


}
