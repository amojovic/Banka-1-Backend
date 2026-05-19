package com.banka1.tradingservice.dividend.service;

import com.banka1.order.entity.Portfolio;
import com.banka1.order.repository.PortfolioRepository;
import com.banka1.tradingservice.dividend.client.DividendDataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * WP-14 (Celina 3.7): orkestracija obracuna i isplate kvartalne dividende.
 *
 * <p>Za svaku STOCK hartiju sa pozitivnom {@code dividendYield}, za svakog
 * drzaoca ({@code Portfolio} red, {@code listingType=STOCK}, {@code quantity>0}),
 * delegira isplatu na {@link DividendPayoutExecutor#payoutForHolder}. Svaka
 * isplata tece u sopstvenoj transakciji (poseban bean — vidi
 * {@link DividendPayoutExecutor}), pa jedna greska ne obara ceo obracun.
 *
 * <p>Formula i poreska logika (15% za licne pozicije, bez poreza za pozicije
 * banke) su u {@link DividendPayoutExecutor}.
 *
 * <p>WP-17 (Celina 4.3): posle isplate {@code Portfolio} drzaocima, za svaku
 * dividendnu hartiju dodatno obradjuje i {@link com.banka1.tradingservice.funds.domain.FundHolding}
 * pozicije te hartije preko {@link FundDividendProcessor} — priliv u fond plus
 * politika {@code REINVEST}/{@code DISTRIBUTE}.
 *
 * <h2>Poznata pojednostavljenja (vidi WP-14 izvestaj)</h2>
 * <ul>
 *   <li><b>Detekcija pozicija banke:</b> {@code Portfolio} nema per-poziciju
 *       marker vlasnistva banke; sve {@code Portfolio} STOCK pozicije se tretiraju
 *       kao licne (oporezovane). Vidi {@link DividendPayoutExecutor#isBankHeld}.</li>
 *   <li><b>Izbor racuna:</b> isplata ide na RSD tekuci racun drzaoca (neto se
 *       konvertuje u RSD bez provizije) — dozvoljeni spec fallback. Vidi
 *       {@link DividendPayoutExecutor}.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendDistributionService {

    private final DividendDataClient dividendDataClient;
    private final PortfolioRepository portfolioRepository;
    private final DividendPayoutExecutor payoutExecutor;
    private final FundDividendProcessor fundDividendProcessor;

    /**
     * Izvrsava obracun i isplatu dividende za sve drzaoce akcija na zadati dan.
     *
     * <p>Po hartiji: prvo isplacuje {@code Portfolio} STOCK drzaoce (WP-14),
     * zatim obradjuje {@code FundHolding} pozicije te hartije (WP-17 — priliv u
     * fond + politika fonda).
     *
     * @param asOf datum obracuna (poslednji radni dan kvartala)
     * @return broj uspesno izvrsenih isplata drzaocima ({@code Portfolio});
     *         obrada fondova se loguje zasebno
     */
    public int distribute(LocalDate asOf) {
        List<DividendDataClient.DividendData> stocks = dividendDataClient.fetchAll();
        if (stocks.isEmpty()) {
            log.info("Dividend distribute({}): nema dividendnih podataka — nista za isplatu.", asOf);
            return 0;
        }

        int paid = 0;
        int fundsProcessed = 0;
        for (DividendDataClient.DividendData stock : stocks) {
            if (!hasPositiveYield(stock)) {
                continue;
            }
            List<Portfolio> holders = portfolioRepository.findByListingIdStockHolders(stock.listingId());
            for (Portfolio holder : holders) {
                if (payOneHolderSafely(stock, holder, asOf)) {
                    paid++;
                }
            }
            // WP-17: posle drzaoca-pojedinaca, obradi fond-pozicije iste hartije.
            fundsProcessed += processFundsSafely(stock, asOf);
        }
        log.info("Dividend distribute({}): izvrseno {} isplata drzaocima, {} fond-pozicija obradjeno.",
                asOf, paid, fundsProcessed);
        return paid;
    }

    private int processFundsSafely(DividendDataClient.DividendData stock, LocalDate asOf) {
        try {
            return fundDividendProcessor.processStockForFunds(stock, asOf);
        } catch (Exception ex) {
            log.error("Dividend: obrada fondova pala za listingId={} ticker={} datum={}",
                    stock.listingId(), stock.ticker(), asOf, ex);
            return 0;
        }
    }

    private boolean payOneHolderSafely(DividendDataClient.DividendData stock, Portfolio holder, LocalDate asOf) {
        try {
            return payoutExecutor.payoutForHolder(stock, holder, asOf);
        } catch (Exception ex) {
            log.error("Dividend: isplata pala za userId={} listingId={} datum={}",
                    holder.getUserId(), stock.listingId(), asOf, ex);
            return false;
        }
    }

    private boolean hasPositiveYield(DividendDataClient.DividendData stock) {
        return stock.dividendYield() != null && stock.dividendYield().signum() > 0;
    }
}
