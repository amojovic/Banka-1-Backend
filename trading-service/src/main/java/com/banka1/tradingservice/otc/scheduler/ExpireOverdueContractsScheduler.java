package com.banka1.tradingservice.otc.scheduler;

import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OptionContractStatus;
import com.banka1.tradingservice.otc.domain.OtcContractExpiryReminder;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import com.banka1.tradingservice.otc.repository.OtcContractExpiryReminderRepository;
import com.banka1.tradingservice.otc.service.OtcPortfolioService;
import com.banka1.tradingservice.otc.service.OtcNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * PR_32 Phase 12 KRIT #4: dnevni cron job koji flipuje istekle OTC ugovore
 * iz {@code ACTIVE} u {@code EXPIRED}.
 *
 * <p>Cron: 00:05 svakog dana — pomeren 5 minuta od ponoci zbog mogucnosti
 * da OTC exercise saga jos uvek tece za ugovor cija je settlementDate "danas".
 * "Strogi" exercise period je do 23:59:59 prethodnog dana, ovo cisti repove.
 *
 * <p>Posle ovog cron-a, prodavac dobija svoje akcije nazad (rezervacija se
 * oslobadja). Note: jos uvek se zasebno oslobadja {@code reservedQuantity}
 * preko OTC SAGA-e ako je exercise pao; ovaj cron je samo za TIME-OUT slucaj.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpireOverdueContractsScheduler {

    private final OptionContractRepository contractRepo;
    private final OtcPortfolioService portfolioService;
    private final OtcContractExpiryReminderRepository reminderRepository;
    private final OtcNotificationService notificationService;

    @org.springframework.beans.factory.annotation.Value("${otc.contract.expiration-notification-days:3}")
    private int reminderDays;

    @Scheduled(cron = "${otc.expire.cron:0 5 0 * * *}")
    @Transactional
    public void expireOverdueContracts() {
        LocalDate today = LocalDate.now();
        List<OptionContract> stale =
                contractRepo.findByStatusAndSettlementDateBefore(OptionContractStatus.ACTIVE, today);
        if (stale.isEmpty()) {
            log.debug("expireOverdueContracts: no contracts to expire (today={})", today);
            return;
        }
        for (OptionContract contract : stale) {
            contract.setStatus(OptionContractStatus.EXPIRED);
            contractRepo.save(contract);
            portfolioService.releaseForContract(contract.getSellerId(), contract.getStockTicker(), contract.getAmount());
            log.info("Expired OTC option contract id={} ticker={} buyer={} seller={} settled={}",
                    contract.getId(),
                    contract.getStockTicker(),
                    contract.getBuyerId(),
                    contract.getSellerId(),
                    contract.getSettlementDate());
        }
        log.info("expireOverdueContracts: expired {} contracts (today={})", stale.size(), today);
    }

    @Scheduled(cron = "${otc.contract.expiration-check-cron:0 30 8 * * *}")
    @Transactional
    public void sendExpiryReminders() {
        LocalDate targetDate = LocalDate.now().plusDays(reminderDays);
        List<OptionContract> contracts = contractRepo.findByStatusAndSettlementDate(OptionContractStatus.ACTIVE, targetDate);
        for (OptionContract contract : contracts) {
            if (reminderRepository.existsByContractIdAndReminderDays(contract.getId(), reminderDays)) {
                continue;
            }
            notificationService.sendExpiryReminder(contract, reminderDays);
            OtcContractExpiryReminder reminder = new OtcContractExpiryReminder();
            reminder.setContractId(contract.getId());
            reminder.setReminderDays(reminderDays);
            reminderRepository.save(reminder);
        }
    }
}
