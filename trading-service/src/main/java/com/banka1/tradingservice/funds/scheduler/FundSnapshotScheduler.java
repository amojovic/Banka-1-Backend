package com.banka1.tradingservice.funds.scheduler;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import com.banka1.tradingservice.funds.repository.InvestmentFundRepository;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * WP-18 (Celina 4.4): mesecni cron koji snima vrednost svakog investicionog
 * fonda u {@link FundValueSnapshot}.
 *
 * <p>{@code vrednostFonda} i {@code profit} su inace izvedeni — racunaju se per
 * query u {@link InvestmentFundService} i ne cuvaju na {@code InvestmentFund}.
 * Bez serije istorijskih vrednosti statistika fonda (godisnji prinos,
 * volatilnost, max drawdown, reward-to-variability) ne moze da se izracuna. Ovaj
 * scheduler 1. u mesecu materijalizuje izvedenu valuaciju u jedan red po fondu.
 *
 * <p>Idempotentan na {@code (fundId, snapshotDate)} — ako za dati fond vec
 * postoji snapshot za danasnji datum, taj fond se preskace (npr. ako se cron
 * okine dva puta isti dan, ili posle manuelnog upisa).
 *
 * <p>Greska pri valuaciji jednog fonda (npr. nedostupan market-service) se
 * loguje i guta — ostali fondovi se i dalje snimaju.
 *
 * <p>Obican {@code @Scheduled}, bez ShedLock-a — konzistentno sa ostalim
 * trading schedulerima ({@code DividendScheduler}, {@code OtcExpiryReminderScheduler},
 * {@code ExpireOverdueContractsScheduler}). {@link Clock} je injektovan radi
 * testabilnosti {@code snapshotDate}-a.
 */
@Slf4j
@Component
public class FundSnapshotScheduler {

    private final InvestmentFundRepository fundRepository;
    private final FundValueSnapshotRepository snapshotRepository;
    private final InvestmentFundService fundService;
    private final Clock clock;

    /**
     * @param fundRepository     repozitorijum fondova
     * @param snapshotRepository repozitorijum snapshot-a (idempotencija + upis)
     * @param fundService        servis koji racuna izvedenu valuaciju fonda
     * @param clock              sat za odredjivanje {@code snapshotDate}-a (injektabilan radi testova)
     */
    public FundSnapshotScheduler(InvestmentFundRepository fundRepository,
                                 FundValueSnapshotRepository snapshotRepository,
                                 InvestmentFundService fundService,
                                 Clock clock) {
        this.fundRepository = fundRepository;
        this.snapshotRepository = snapshotRepository;
        this.fundService = fundService;
        this.clock = clock;
    }

    /**
     * Mesecni cron — 1. u mesecu u 00:30 (default {@code fund.snapshot.cron}).
     * Za svaki ne-obrisani fond snima {@link FundValueSnapshot} za danasnji datum,
     * preskacuci fondove koji za danas vec imaju snapshot.
     */
    @Scheduled(cron = "${fund.snapshot.cron:0 30 0 1 * *}")
    public void captureMonthlySnapshots() {
        LocalDate today = LocalDate.now(clock);
        List<InvestmentFund> funds = fundRepository.findByDeletedFalseOrderByNazivAsc();
        if (funds.isEmpty()) {
            log.debug("FundSnapshotScheduler: nema fondova — preskacem ({}).", today);
            return;
        }
        int written = 0;
        int skipped = 0;
        for (InvestmentFund fund : funds) {
            if (snapshotRepository.existsByFundIdAndSnapshotDate(fund.getId(), today)) {
                log.debug("FundSnapshotScheduler: fond {} vec ima snapshot za {} — preskacem.",
                        fund.getId(), today);
                skipped++;
                continue;
            }
            try {
                InvestmentFundService.FundValuation valuation = fundService.computeFundValuation(fund.getId());
                snapshotRepository.save(FundValueSnapshot.builder()
                        .fundId(fund.getId())
                        .snapshotDate(today)
                        .totalValue(valuation.totalValue())
                        .profit(valuation.profit())
                        .build());
                written++;
                log.info("FundSnapshotScheduler: snapshot fonda {} za {} — vrednost={} profit={}",
                        fund.getId(), today, valuation.totalValue(), valuation.profit());
            } catch (Exception ex) {
                log.error("FundSnapshotScheduler: snapshot fonda {} za {} NIJE upisan: {}",
                        fund.getId(), today, ex.toString());
            }
        }
        log.info("FundSnapshotScheduler: zavrseno za {} — {} upisano, {} preskoceno, {} fondova ukupno.",
                today, written, skipped, funds.size());
    }
}
