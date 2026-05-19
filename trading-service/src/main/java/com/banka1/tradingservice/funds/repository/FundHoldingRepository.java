package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundHolding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FundHoldingRepository extends JpaRepository<FundHolding, Long> {

    List<FundHolding> findByFundIdAndDeletedFalse(Long fundId);

    Optional<FundHolding> findByFundIdAndStockTickerAndDeletedFalse(Long fundId, String stockTicker);

    /**
     * WP-17 (Celina 4.3): sve aktivne holding-pozicije date hartije, preko svih
     * fondova. Kvartalna isplata dividende ({@code DividendDistributionService})
     * je koristi da pronadje koje fondove neka dividendna hartija dotice.
     *
     * @param stockTicker ticker hartije koja isplacuje dividendu
     * @return aktivni ({@code deleted=false}) holding-redovi te hartije
     */
    List<FundHolding> findByStockTickerAndDeletedFalse(String stockTicker);
}
