package com.banka1.transfer.client;

import com.banka1.transfer.dto.client.AccountDto;
import com.banka1.transfer.dto.client.PaymentDto;
import com.banka1.transfer.dto.client.UpdatedBalanceResponseDto;

import java.math.BigDecimal;
/**
 * Interfejs za komunikaciju sa servisom za upravljanje računima (Account Service).
 */
public interface AccountClient {
    /**
     * Dobavlja detaljne informacije o bankovnom računu na osnovu broja računa.
     * @param accountNumber jedinstveni broj računa
     * @return DTO sa podacima o vlasniku, valuti i balansu
     */
    AccountDto getAccountDetails(String accountNumber);
    /**
     * Izvršava atomsku transakciju transfera sredstava između dva računa u Account servisu.
     * @param paymentDto podaci o pošiljaocu, primaocu, iznosima i proviziji
     * @return DTO sa ažuriranim stanjima na oba računa
     */
    UpdatedBalanceResponseDto executeTransfer(PaymentDto paymentDto);
}