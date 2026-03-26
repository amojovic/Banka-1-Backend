package com.banka1.transfer.service;

import com.banka1.transfer.dto.requests.TransferRequestDto;
import com.banka1.transfer.dto.responses.TransferResponseDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Primarni servisni interfejs koji definiše poslovne operacije nad transferima.
 * Pruža metode za izvršavanje transakcija i pretragu istorije po različitim kriterijumima.
 */
public interface TransferService {
    /**
     * Izvršava kompletan proces prenosa sredstava (interni ili kros-valutni).
     * @param request Podaci o pošiljaocu, primaocu, iznosu i 2FA verifikaciji.
     * @return DTO sa potvrdom o izvršenom prenosu i podacima o proviziji/kursu.
     */
    TransferResponseDto executeTransfer(TransferRequestDto request);
    /**
     * Dobavlja paginiranu istoriju svih transfera za određenog klijenta.
     * @param clientId ID vlasnika računa.
     * @param pageable Parametri za paginaciju i sortiranje.
     * @return Stranica sa rezultatima transfera.
     */
    Page<TransferResponseDto> getClientTransfers(Long clientId, Pageable pageable);
    /**
     * Dobavlja podatke o specifičnom transferu na osnovu poslovnog broja naloga.
     * @param orderNumber Jedinstveni kod transakcije (TRF-...).
     * @return Detaljni podaci o transferu.
     */
    TransferResponseDto getTransferDetails(String orderNumber);
    /**
     * Dobavlja sve transfere u kojima je određeni račun učestvovao kao pošiljalac ili primalac.
     * @param accountNumber Broj bankovnog računa.
     * @param pageable Parametri za paginaciju.
     * @return Stranica sa rezultatima transfera vezanih za račun.
     */
    Page<TransferResponseDto> getTransfersByAccountNumber(String accountNumber, Pageable pageable);
}
