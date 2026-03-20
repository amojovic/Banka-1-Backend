package com.banka1.employeeService.scheduled;

import com.banka1.employeeService.repository.ConfirmationTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Zakazani zadatak koji periodicno brise istekle confirmation tokene iz baze.
 * Pokrace se jednom na sat i uklanja sve tokene ciji je rok vazenja prosao.
 */
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    /** Repozitorijum za pristup i brisanje confirmation tokena. */
    private final ConfirmationTokenRepository confirmationTokenRepository;

    /**
     * Brise sve confirmation tokene kojima je istekao rok vazenja.
     * Pokrace se automatski jednom na sat (sekunda 0, minut 0, svaki sat).
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanUpExpiredTokens() {
        confirmationTokenRepository.deleteAllByExpirationDateTimeNotNullAndExpirationDateTimeBefore(LocalDateTime.now());
    }
}
