package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import com.banka1.tradingservice.funds.dto.FundStatisticsDto;
import com.banka1.tradingservice.funds.repository.FundValueSnapshotRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;

/**
 * WP-18 (Celina 4.4): obracun statistike performansi investicionog fonda iz
 * serije {@link FundValueSnapshot} redova (hronoloski, po datumu).
 *
 * <h2>Definicije</h2>
 * Neka je {@code v[0..n]} serija {@code totalValue}-a snapshot-a. Mesecni prinos
 * za interval {@code i} je {@code r[i] = (v[i] - v[i-1]) / v[i-1]}.
 * <ul>
 *   <li><b>Volatilnost</b> = <i>uzoracka</i> standardna devijacija mesecnih
 *       prinosa {@code r[]}: {@code sqrt( Sigma (r[i]-mean)^2 / (m-1) )},
 *       {@code m} = broj prinosa.</li>
 *   <li><b>Godisnji prinos</b> = anualizovan ukupan prinos preko posmatranog
 *       perioda: {@code (v[last]/v[0])^(12/numMonths) - 1}, {@code numMonths = m}.</li>
 *   <li><b>Max drawdown</b> = najveci pad od tekuceg vrha do dna:
 *       {@code max nad j od (runningPeak[j] - v[j]) / runningPeak[j]} ({@code >= 0}).</li>
 *   <li><b>Reward-to-variability</b> (Sharpe-like) = {@code prosecan mesecni
 *       prinos / volatilnost}, uz <b>risk-free stopu 0</b> (simulacija — spec ne
 *       definise bezrizicnu stopu). Kad je volatilnost 0, vraca {@code null}
 *       (izbegava deljenje nulom).</li>
 * </ul>
 * Sve metrike su <b>frakcije</b> (npr. {@code 0.12} = 12%), ne procenti.
 *
 * <h2>Determinizam</h2>
 * Interni obracun ide u {@code BigDecimal}-u uz {@link MathContext#DECIMAL128}
 * (34 znacajne cifre); finalne metrike se zaokruzuju na {@link #METRIC_SCALE}
 * decimala {@code HALF_UP}. Anualizacija koristi {@link StrictMath#pow} (rational
 * eksponent {@code 12/m}) zbog reproducibilnosti — verifikovano da daje iste
 * zaokruzene vrednosti kao obracun visoke preciznosti.
 *
 * <h2>Minimalan broj snapshot-a</h2>
 * Metrike imaju smisla tek kad postoji dovoljno istorije. Ispod
 * {@link #MIN_SNAPSHOTS_FOR_STATS} snapshot-a (podesivo preko
 * {@code fund.statistics.min-snapshots}) statistika se NE racuna — vraca se
 * {@link FundStatisticsDto} sa {@code metricsAvailable=false} i {@code null}
 * metrikama.
 */
@Slf4j
@Service
public class FundStatisticsService {

    /**
     * Podrazumevani minimalan broj snapshot-a da bi metrike bile dostupne.
     * Tri snapshot-a daju bar dva mesecna prinosa — minimum za uzoracku
     * standardnu devijaciju (delilac {@code m-1 >= 1}).
     */
    public static final int DEFAULT_MIN_SNAPSHOTS_FOR_STATS = 3;

    /** Broj decimala na koji se zaokruzuju finalne metrike. */
    public static final int METRIC_SCALE = 8;

    private static final MathContext MC = MathContext.DECIMAL128;

    private final FundValueSnapshotRepository snapshotRepository;

    /**
     * Efektivni minimalan broj snapshot-a (>= 2 da uzoracka std. dev. ima smisla).
     * Podesiv preko {@code fund.statistics.min-snapshots}; default
     * {@link #DEFAULT_MIN_SNAPSHOTS_FOR_STATS}.
     */
    private final int minSnapshotsForStats;

    public FundStatisticsService(
            FundValueSnapshotRepository snapshotRepository,
            @Value("${fund.statistics.min-snapshots:" + DEFAULT_MIN_SNAPSHOTS_FOR_STATS + "}")
            int minSnapshotsForStats) {
        this.snapshotRepository = snapshotRepository;
        this.minSnapshotsForStats = Math.max(2, minSnapshotsForStats);
    }

    /** @return efektivni minimalan broj snapshot-a potreban za obracun metrika. */
    public int getMinSnapshotsForStats() {
        return minSnapshotsForStats;
    }

    /**
     * Racuna statistiku fonda iz njegove snapshot serije.
     *
     * @param fundId ID fonda
     * @return {@link FundStatisticsDto}; ispod minimuma snapshot-a sve metrike su
     *         {@code null} i {@code metricsAvailable=false}
     */
    @Transactional(readOnly = true)
    public FundStatisticsDto computeStatistics(Long fundId) {
        List<FundValueSnapshot> series = snapshotRepository.findByFundIdOrderBySnapshotDateAsc(fundId);
        return computeFromSeries(fundId, series.stream().map(FundValueSnapshot::getTotalValue).toList());
    }

    /**
     * Cista funkcija obracuna metrika iz hronoloske serije {@code totalValue}-a.
     * Izdvojena radi testiranja sa poznatom serijom.
     *
     * @param fundId ID fonda (samo za popunjavanje DTO-a)
     * @param values serija {@code totalValue}-a, najstariji prvi
     * @return {@link FundStatisticsDto}
     */
    FundStatisticsDto computeFromSeries(Long fundId, List<BigDecimal> values) {
        int count = values.size();
        FundStatisticsDto.FundStatisticsDtoBuilder dto = FundStatisticsDto.builder()
                .fundId(fundId)
                .snapshotCount(count)
                .minSnapshotsRequired(minSnapshotsForStats);

        if (count < minSnapshotsForStats) {
            log.debug("FundStatistics: fond {} ima {} snapshot-a (< {}) — metrike nedostupne.",
                    fundId, count, minSnapshotsForStats);
            return dto.metricsAvailable(false).build();
        }

        // Mesecni prinosi r[i] = (v[i] - v[i-1]) / v[i-1].
        int m = count - 1;
        BigDecimal[] returns = new BigDecimal[m];
        for (int i = 1; i < count; i++) {
            BigDecimal prev = values.get(i - 1);
            if (prev.signum() == 0) {
                // v[i-1] == 0: prinos nedefinisan (deljenje nulom). Degenerisana
                // serija — metrike se ne mogu pouzdano izracunati.
                log.warn("FundStatistics: fond {} ima nultu vrednost u snapshot seriji na poziciji {} "
                        + "— metrike nedostupne.", fundId, i - 1);
                return dto.metricsAvailable(false).build();
            }
            returns[i - 1] = values.get(i).subtract(prev).divide(prev, MC);
        }

        BigDecimal meanReturn = mean(returns);
        BigDecimal volatility = sampleStdDev(returns, meanReturn);
        BigDecimal annualized = annualizedReturn(values.get(0), values.get(count - 1), m);
        BigDecimal maxDrawdown = maxDrawdown(values);
        BigDecimal rewardToVariability = (volatility.signum() == 0)
                ? null
                : meanReturn.divide(volatility, MC).setScale(METRIC_SCALE, RoundingMode.HALF_UP);

        return dto.metricsAvailable(true)
                .annualizedReturn(annualized.setScale(METRIC_SCALE, RoundingMode.HALF_UP))
                .volatility(volatility.setScale(METRIC_SCALE, RoundingMode.HALF_UP))
                .maxDrawdown(maxDrawdown.setScale(METRIC_SCALE, RoundingMode.HALF_UP))
                .rewardToVariabilityRatio(rewardToVariability)
                .build();
    }

    private static BigDecimal mean(BigDecimal[] values) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            sum = sum.add(v);
        }
        return sum.divide(BigDecimal.valueOf(values.length), MC);
    }

    /**
     * Uzoracka standardna devijacija: {@code sqrt( Sigma (x-mean)^2 / (n-1) )}.
     * Za {@code n == 1} (jedan prinos) vraca 0 — nema disperzije za meriti.
     */
    private static BigDecimal sampleStdDev(BigDecimal[] values, BigDecimal mean) {
        if (values.length < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal sumSq = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            BigDecimal d = v.subtract(mean);
            sumSq = sumSq.add(d.multiply(d, MC));
        }
        BigDecimal variance = sumSq.divide(BigDecimal.valueOf(values.length - 1L), MC);
        return variance.sqrt(MC);
    }

    /**
     * Anualizovan ukupan prinos: {@code (last/first)^(12/numMonths) - 1}.
     * Eksponent {@code 12/numMonths} je racionalan; {@link StrictMath#pow}
     * obezbedjuje reproducibilan rezultat.
     */
    private static BigDecimal annualizedReturn(BigDecimal first, BigDecimal last, int numMonths) {
        double ratio = last.divide(first, MC).doubleValue();
        double annual = StrictMath.pow(ratio, 12.0 / numMonths) - 1.0;
        return BigDecimal.valueOf(annual);
    }

    /**
     * Max drawdown: {@code max nad j od (runningPeak[j] - v[j]) / runningPeak[j]}.
     * Nenegativna frakcija; 0 za monotono rastucu (ili konstantnu) seriju.
     */
    private static BigDecimal maxDrawdown(List<BigDecimal> values) {
        BigDecimal peak = values.get(0);
        BigDecimal maxDd = BigDecimal.ZERO;
        for (BigDecimal v : values) {
            if (v.compareTo(peak) > 0) {
                peak = v;
            }
            if (peak.signum() > 0) {
                BigDecimal dd = peak.subtract(v).divide(peak, MC);
                if (dd.compareTo(maxDd) > 0) {
                    maxDd = dd;
                }
            }
        }
        return maxDd;
    }
}
