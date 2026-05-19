package com.banka1.tradingservice.otc.scheduler;

import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.notification.OtcNotificationProducer;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * WP-15 (Celina 4.1): daily cron that reminds option-contract buyers that their
 * contract is about to expire.
 *
 * <p>Spec (Celina 4): the OTC flow should notify a party "when an option
 * contract is expiring in N days (e.g. 3 days before)". This scheduler finds
 * every {@link OptionContract} that is still {@code ACTIVE}, whose
 * {@code settlementDate} falls within the reminder window
 * ({@code <= today + N days}) and that has not already been reminded
 * ({@code expiryReminderSent = false}), publishes an {@code otc.contract_expiring}
 * notification to the contract buyer, and flips {@code expiryReminderSent} so the
 * reminder is sent at most once per contract.
 *
 * <p>The reminder window {@code N} defaults to 3 days ({@code otc.expiry-reminder.days});
 * the cron defaults to 00:15 daily ({@code otc.expiry-reminder.cron}) — staggered
 * after {@link ExpireOverdueContractsScheduler} (00:05) so a contract that
 * settles today is still reminded before it is expired.
 *
 * <p>Plain {@code @Scheduled} cron with no ShedLock — single-instance deployment,
 * consistent with {@link ExpireOverdueContractsScheduler}.
 */
@Slf4j
@Component
public class OtcExpiryReminderScheduler {

    private final OptionContractRepository contractRepo;
    private final OtcNotificationProducer notificationProducer;
    private final int reminderDays;

    public OtcExpiryReminderScheduler(OptionContractRepository contractRepo,
                                      OtcNotificationProducer notificationProducer,
                                      @Value("${otc.expiry-reminder.days:3}") int reminderDays) {
        this.contractRepo = contractRepo;
        this.notificationProducer = notificationProducer;
        this.reminderDays = reminderDays;
    }

    @Scheduled(cron = "${otc.expiry-reminder.cron:0 15 0 * * *}")
    @Transactional
    public void sendExpiryReminders() {
        LocalDate cutoff = LocalDate.now().plusDays(reminderDays);
        List<OptionContract> expiring = contractRepo.findExpiringForReminder(cutoff);
        if (expiring.isEmpty()) {
            log.debug("sendExpiryReminders: no contracts within {}-day window (cutoff={})",
                    reminderDays, cutoff);
            return;
        }
        for (OptionContract contract : expiring) {
            notificationProducer.notifyContractExpiring(
                    contract.getId(), contract.getBuyerId(), contract.getSettlementDate());
            contract.setExpiryReminderSent(true);
            contractRepo.save(contract);
            log.info("OTC expiry reminder sent for contract id={} ticker={} buyer={} settles={}",
                    contract.getId(),
                    contract.getStockTicker(),
                    contract.getBuyerId(),
                    contract.getSettlementDate());
        }
        log.info("sendExpiryReminders: reminded {} contracts within {}-day window (cutoff={})",
                expiring.size(), reminderDays, cutoff);
    }
}
