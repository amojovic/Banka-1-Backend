package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundDividendDistribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface FundDividendDistributionRepository extends JpaRepository<FundDividendDistribution, Long> {

    Optional<FundDividendDistribution> findByFundIdAndStockTickerAndPaymentDate(Long fundId, String stockTicker, LocalDate paymentDate);
}
