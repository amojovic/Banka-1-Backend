package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundDividendPayout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FundDividendPayoutRepository extends JpaRepository<FundDividendPayout, Long> {

    List<FundDividendPayout> findByDistributionId(Long distributionId);
}
