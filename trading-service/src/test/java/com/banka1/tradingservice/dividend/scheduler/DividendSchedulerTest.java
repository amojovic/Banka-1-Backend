package com.banka1.tradingservice.dividend.scheduler;

import com.banka1.tradingservice.dividend.service.DividendDistributionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP-14: unit testovi za {@link DividendScheduler} — gejt-predikat
 * "poslednji radni dan kvartalnog meseca" i ponasanje dnevnog cron-a.
 */
@ExtendWith(MockitoExtension.class)
class DividendSchedulerTest {

    @Mock
    private DividendDistributionService distributionService;

    private DividendScheduler schedulerOn(LocalDate date) {
        Clock fixed = Clock.fixed(date.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));
        return new DividendScheduler(distributionService, fixed);
    }

    // -------------------- qualifying dates --------------------

    @Test
    void qualifies_lastWeekdayOfMarchJuneSeptemberDecember() {
        // 2026: 31.03 (uto), 30.06 (uto), 30.09 (sre), 31.12 (cet) — svi radni i poslednji dan
        assertTrue(schedulerOn(LocalDate.of(2026, 3, 31))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 3, 31)));
        assertTrue(schedulerOn(LocalDate.of(2026, 6, 30))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 6, 30)));
        assertTrue(schedulerOn(LocalDate.of(2026, 9, 30))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 9, 30)));
        assertTrue(schedulerOn(LocalDate.of(2026, 12, 31))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void qualifies_lastBusinessDayWhenMonthEndsOnWeekend() {
        // 30.06.2024 je nedelja -> poslednji radni dan je petak 28.06.2024
        DividendScheduler s = schedulerOn(LocalDate.of(2024, 6, 28));
        assertTrue(s.isLastBusinessDayOfQuarterMonth(LocalDate.of(2024, 6, 28)),
                "petak pre vikend-kraja meseca je poslednji radni dan");
        // 31.03.2024 je nedelja -> poslednji radni dan je petak 29.03.2024
        assertTrue(schedulerOn(LocalDate.of(2024, 3, 29))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2024, 3, 29)));
    }

    // -------------------- non-qualifying dates --------------------

    @Test
    void doesNotQualify_weekendMonthEnd() {
        // 30.06.2024 je nedelja — sam kraj meseca, ali vikend
        assertFalse(schedulerOn(LocalDate.of(2024, 6, 30))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2024, 6, 30)));
        // 31.03.2024 je nedelja
        assertFalse(schedulerOn(LocalDate.of(2024, 3, 31))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2024, 3, 31)));
    }

    @Test
    void doesNotQualify_weekdayThatIsNotLastBusinessDay() {
        // 30.03.2026 (pon) je radni dan u kvartalnom mesecu, ali NIJE poslednji
        assertFalse(schedulerOn(LocalDate.of(2026, 3, 30))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 3, 30)));
        // 27.06.2024 (cet) — radni, ali poslednji radni dan je 28.06 (pet)
        assertFalse(schedulerOn(LocalDate.of(2024, 6, 27))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2024, 6, 27)));
    }

    @Test
    void doesNotQualify_lastBusinessDayOfNonQuarterMonth() {
        // 29.05.2026 (pet) je poslednji radni dan maja — ali maj nije kvartalni mesec
        assertFalse(schedulerOn(LocalDate.of(2026, 5, 29))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 5, 29)));
        // 27.02.2026 (pet) — poslednji radni dan februara, nije kvartalni mesec
        assertFalse(schedulerOn(LocalDate.of(2026, 2, 27))
                .isLastBusinessDayOfQuarterMonth(LocalDate.of(2026, 2, 27)));
    }

    // -------------------- cron behaviour --------------------

    @Test
    void cron_triggersDistributionOnQualifyingDay() {
        when(distributionService.distribute(LocalDate.of(2026, 3, 31))).thenReturn(5);
        DividendScheduler scheduler = schedulerOn(LocalDate.of(2026, 3, 31));

        scheduler.runQuarterlyDividendPayout();

        verify(distributionService).distribute(eq(LocalDate.of(2026, 3, 31)));
    }

    @Test
    void cron_skipsDistributionOnNonQualifyingDay() {
        DividendScheduler scheduler = schedulerOn(LocalDate.of(2026, 5, 15));

        scheduler.runQuarterlyDividendPayout();

        verify(distributionService, never()).distribute(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cron_triggersOnWeekendShiftedLastBusinessDay() {
        when(distributionService.distribute(LocalDate.of(2024, 6, 28))).thenReturn(3);
        DividendScheduler scheduler = schedulerOn(LocalDate.of(2024, 6, 28));

        scheduler.runQuarterlyDividendPayout();

        verify(distributionService).distribute(eq(LocalDate.of(2024, 6, 28)));
    }
}
