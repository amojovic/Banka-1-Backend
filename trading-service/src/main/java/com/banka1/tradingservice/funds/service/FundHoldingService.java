package com.banka1.tradingservice.funds.service;

import com.banka1.tradingservice.funds.domain.FundHolding;
import com.banka1.tradingservice.funds.repository.FundHoldingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Servis za FundHolding upravljanje (PR_14 C14.7).
 *
 * <p>Pruza:
 * <ul>
 *   <li>{@code addOrUpdate} — kada FUND_INVEST saga kupuje hartije za fond,
 *       belezi/uvecava postojeci holding sa weighted-average unit price-om.</li>
 *   <li>{@code reduce} — pri prodaji hartija (FUND_REDEEM_WITH_LIQUIDATION)
 *       smanjuje quantity; ako padne na 0, holding se soft-delete-uje.</li>
 *   <li>{@code listByFund} — sve aktivne (deleted=false) hartije fonda.</li>
 *   <li>{@code calculateHoldingsValue} — suma (quantity * latestPrice) preko
 *       PriceLookup-a; koristi se u InvestmentFundService.computeFundValue
 *       za "vrednost fonda" prikaz.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundHoldingService {

    private final FundHoldingRepository repository;

    /**
     * Dodaje/azurira holding za par (fund, ticker). avgUnitPrice se rekalkulise:
     * <pre>
     * newAvg = (oldQty * oldAvg + addedQty * unitPrice) / (oldQty + addedQty)
     * </pre>
     */
    @Transactional
    public FundHolding addOrUpdate(Long fundId, String stockTicker, int addedQuantity, BigDecimal unitPrice) {
        if (addedQuantity <= 0 || unitPrice.signum() <= 0) {
            throw new IllegalArgumentException("addedQuantity i unitPrice moraju biti > 0.");
        }
        Optional<FundHolding> existing = repository.findByFundIdAndStockTickerAndDeletedFalse(fundId, stockTicker);
        FundHolding holding;
        if (existing.isPresent()) {
            holding = existing.get();
            int oldQty = holding.getQuantity();
            BigDecimal oldAvg = holding.getAvgUnitPrice();
            int newQty = oldQty + addedQuantity;
            BigDecimal newAvg = oldAvg.multiply(BigDecimal.valueOf(oldQty))
                    .add(unitPrice.multiply(BigDecimal.valueOf(addedQuantity)))
                    .divide(BigDecimal.valueOf(newQty), 4, RoundingMode.HALF_UP);
            holding.setQuantity(newQty);
            holding.setAvgUnitPrice(newAvg);
            holding.setUpdatedAt(LocalDateTime.now());
        } else {
            holding = FundHolding.builder()
                    .fundId(fundId)
                    .stockTicker(stockTicker)
                    .quantity(addedQuantity)
                    .avgUnitPrice(unitPrice.setScale(4, RoundingMode.HALF_UP))
                    .deleted(false)
                    .createdAt(LocalDateTime.now())
                    .build();
        }
        FundHolding saved = repository.save(holding);
        log.info("FundHolding addOrUpdate: fundId={} ticker={} qty={} avg={}",
                fundId, stockTicker, saved.getQuantity(), saved.getAvgUnitPrice());
        return saved;
    }

    /**
     * Smanjuje quantity holdinga. Ako bi rezultat bio 0 ili manje, holding se
     * soft-delete-uje (quantity=0, deleted=true). Ako quantity nije dovoljan,
     * baca {@link IllegalStateException}.
     */
    @Transactional
    public FundHolding reduce(Long fundId, String stockTicker, int reduceBy) {
        if (reduceBy <= 0) {
            throw new IllegalArgumentException("reduceBy mora biti > 0.");
        }
        FundHolding holding = repository.findByFundIdAndStockTickerAndDeletedFalse(fundId, stockTicker)
                .orElseThrow(() -> new IllegalStateException(
                        "Fond " + fundId + " ne poseduje " + stockTicker + " — reduce odbijen."));
        if (holding.getQuantity() < reduceBy) {
            throw new IllegalStateException(
                    "Nedovoljno hartija " + stockTicker + " u fondu " + fundId
                            + " (raspolozivo=" + holding.getQuantity() + ", trazeno=" + reduceBy + ").");
        }
        int newQty = holding.getQuantity() - reduceBy;
        holding.setQuantity(newQty);
        holding.setUpdatedAt(LocalDateTime.now());
        if (newQty == 0) {
            holding.setDeleted(true);
        }
        FundHolding saved = repository.save(holding);
        log.info("FundHolding reduce: fundId={} ticker={} reduceBy={} newQty={} deleted={}",
                fundId, stockTicker, reduceBy, newQty, saved.isDeleted());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<FundHolding> listByFund(Long fundId) {
        return repository.findByFundIdAndDeletedFalse(fundId);
    }
}
