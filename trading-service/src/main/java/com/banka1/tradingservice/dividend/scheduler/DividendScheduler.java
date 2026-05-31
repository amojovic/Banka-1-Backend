package com.banka1.tradingservice.dividend.scheduler;

import com.banka1.tradingservice.dividend.service.DividendDistributionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.util.Set;

/**
 * WP-14 (Celina 3.7): dnevni cron koji pokrece kvartalnu isplatu dividende.
 *
 * <p>Dividenda se isplacuje kvartalno — poslednjeg radnog dana (Pon-Pet) marta,
 * juna, septembra i decembra. Jedan cron izraz ne moze da izrazi "poslednji
 * radni dan tih meseci", pa cron radi <b>svakog dana</b> ({@code 0 0 1 * * *})
 * a metoda se sama gejtuje: ako "danas" ne ispunjava uslov, odmah vraca.
 *
 * <p>Obican {@code @Scheduled}, bez ShedLock-a — konzistentno sa ostalim
 * trading/order schedulerima ({@code ExpireOverdueContractsScheduler} itd.).
 *
 * <p>{@link Clock} je injektovan radi testabilnosti gejt-predikata.
 */
@Slf4j
@Component
public class DividendScheduler {

    /** Meseci na cijem se kraju isplacuje kvartalna dividenda. */
    private static final Set<Month> QUARTER_END_MONTHS =
            Set.of(Month.MARCH, Month.JUNE, Month.SEPTEMBER, Month.DECEMBER);

    private final DividendDistributionService distributionService;
    private final Clock clock;

    /**
     * @param distributionService servis koji izvrsava obracun i isplatu
     * @param clock               sat za odredjivanje "danasnjeg" datuma (injektabilan radi testova)
     */
    public DividendScheduler(DividendDistributionService distributionService, Clock clock) {
        this.distributionService = distributionService;
        this.clock = clock;
    }

    /**
     * Dnevni cron. Ako je danas poslednji radni dan kvartalnog meseca, pokrece
     * isplatu dividende; inace ne radi nista.
     */
    @Scheduled(cron = "${dividend.payout.cron:0 0 1 * * *}")
    public void runQuarterlyDividendPayout() {
        LocalDate today = LocalDate.now(clock);
        if (!isLastBusinessDayOfQuarterMonth(today)) {
            log.debug("DividendScheduler: {} nije poslednji radni dan kvartala — preskacem.", today);
            return;
        }
        log.info("DividendScheduler: {} je poslednji radni dan kvartala — pokrecem isplatu dividende.", today);
        int paid = distributionService.distribute(today);
        log.info("DividendScheduler: isplata dividende zavrsena za {} ({} isplata).", today, paid);
    }

    /**
     * Da li je zadati datum poslednji radni dan (Pon-Pet) marta, juna,
     * septembra ili decembra.
     *
     * <p>Poslednji radni dan = poslednji dan meseca ako je radni; inace
     * najskoriji prethodni radni dan (vikend se "pomera unazad" do petka).
     *
     * @param date datum koji se proverava
     * @return {@code true} ako je {@code date} poslednji radni dan kvartalnog meseca
     */
    boolean isLastBusinessDayOfQuarterMonth(LocalDate date) {
        if (!QUARTER_END_MONTHS.contains(date.getMonth())) {
            return false;
        }
        if (isWeekend(date)) {
            return false;
        }
        return date.equals(lastBusinessDayOfMonth(date));
    }

    /**
     * Vraca poslednji radni dan meseca kome zadati datum pripada.
     *
     * @param date bilo koji datum u zeljenom mesecu
     * @return poslednji radni dan (Pon-Pet) tog meseca
     */
    private LocalDate lastBusinessDayOfMonth(LocalDate date) {
        LocalDate candidate = date.withDayOfMonth(date.lengthOfMonth());
        while (isWeekend(candidate)) {
            candidate = candidate.minusDays(1);
        }
        return candidate;
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
