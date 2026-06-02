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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DividendDistributionService {

    private final DividendDataClient dividendDataClient;
    private final PortfolioRepository portfolioRepository;
    private final DividendPayoutExecutor payoutExecutor;

    /**
     * Izvrsava obracun i isplatu dividende za sve drzaoce akcija na zadati dan.
     *
     * @param asOf datum obracuna (poslednji radni dan kvartala)
     * @return broj uspesno izvrsenih isplata drzaocima ({@code Portfolio})
     */
    public int distribute(LocalDate asOf) {
        List<DividendDataClient.DividendData> stocks = dividendDataClient.fetchAll();
        if (stocks.isEmpty()) {
            log.info("Dividend distribute({}): nema dividendnih podataka — nista za isplatu.", asOf);
            return 0;
        }

        int paid = 0;
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
        }
        log.info("Dividend distribute({}): izvrseno {} isplata drzaocima.", asOf, paid);
        return paid;
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
