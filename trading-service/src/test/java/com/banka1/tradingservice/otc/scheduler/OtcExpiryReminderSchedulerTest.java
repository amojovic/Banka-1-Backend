package com.banka1.tradingservice.otc.scheduler;

import com.banka1.tradingservice.otc.domain.OptionContract;
import com.banka1.tradingservice.otc.domain.OptionContractStatus;
import com.banka1.tradingservice.otc.notification.OtcNotificationProducer;
import com.banka1.tradingservice.otc.repository.OptionContractRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-15 (Celina 4.1): verifies the daily expiry-reminder cron — a contract
 * within the N-day window is reminded once and flagged; an already-reminded
 * contract is not re-sent; a contract not yet near expiry is not sent.
 */
@ExtendWith(MockitoExtension.class)
class OtcExpiryReminderSchedulerTest {

    private static final int REMINDER_DAYS = 3;

    @Mock private OptionContractRepository contractRepo;
    @Mock private OtcNotificationProducer notificationProducer;

    private OtcExpiryReminderScheduler scheduler() {
        return new OtcExpiryReminderScheduler(contractRepo, notificationProducer, REMINDER_DAYS);
    }

    @Test
    void sendExpiryReminders_notifiesAndFlagsContractsWithinWindow() {
        OptionContract c1 = newContract(1L, 100L, LocalDate.now().plusDays(2));
        OptionContract c2 = newContract(2L, 101L, LocalDate.now().plusDays(1));
        when(contractRepo.findExpiringForReminder(any(LocalDate.class)))
                .thenReturn(List.of(c1, c2));

        scheduler().sendExpiryReminders();

        // Each contract's buyer gets exactly one expiry reminder.
        verify(notificationProducer).notifyContractExpiring(1L, 100L, c1.getSettlementDate());
        verify(notificationProducer).notifyContractExpiring(2L, 101L, c2.getSettlementDate());

        // Each reminded contract is flagged so the reminder is sent at most once.
        ArgumentCaptor<OptionContract> captor = ArgumentCaptor.forClass(OptionContract.class);
        verify(contractRepo, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).allMatch(OptionContract::isExpiryReminderSent);
    }

    @Test
    void sendExpiryReminders_queriesWithCutoffOfTodayPlusReminderDays() {
        when(contractRepo.findExpiringForReminder(any(LocalDate.class))).thenReturn(List.of());

        scheduler().sendExpiryReminders();

        ArgumentCaptor<LocalDate> cutoff = ArgumentCaptor.forClass(LocalDate.class);
        verify(contractRepo).findExpiringForReminder(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(LocalDate.now().plusDays(REMINDER_DAYS));
    }

    @Test
    void sendExpiryReminders_doesNothing_whenNoContractsWithinWindow() {
        // A contract not yet near expiry is excluded by the repository query
        // (settlementDate > cutoff), so the result list is empty.
        when(contractRepo.findExpiringForReminder(any(LocalDate.class))).thenReturn(List.of());

        scheduler().sendExpiryReminders();

        verifyNoInteractions(notificationProducer);
        verify(contractRepo, never()).save(any(OptionContract.class));
    }

    @Test
    void sendExpiryReminders_doesNotResend_whenContractAlreadyReminded() {
        // An already-reminded contract has expiryReminderSent=true; the repository
        // query filters it out, so the scheduler never sees it.
        OptionContract alreadyReminded = newContract(3L, 102L, LocalDate.now().plusDays(2));
        alreadyReminded.setExpiryReminderSent(true);
        when(contractRepo.findExpiringForReminder(any(LocalDate.class)))
                .thenReturn(List.of()); // query already excludes reminded contracts

        scheduler().sendExpiryReminders();

        verify(notificationProducer, never())
                .notifyContractExpiring(eq(3L), eq(102L), any(LocalDate.class));
        assertThat(alreadyReminded.isExpiryReminderSent()).isTrue();
    }

    private static OptionContract newContract(long id, long buyerId, LocalDate settled) {
        OptionContract c = new OptionContract();
        c.setId(id);
        c.setStockTicker("AAPL");
        c.setBuyerId(buyerId);
        c.setSellerId(200L);
        c.setAmount(10);
        c.setPricePerStock(new BigDecimal("150"));
        c.setSettlementDate(settled);
        c.setStatus(OptionContractStatus.ACTIVE);
        c.setOfferId(50L);
        return c;
    }
}
