package com.banka1.tradingservice.otc.repository;

import com.banka1.tradingservice.otc.domain.OtcNegotiationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

@Repository
public interface OtcNegotiationHistoryRepository
        extends JpaRepository<OtcNegotiationHistory, Long>, JpaSpecificationExecutor<OtcNegotiationHistory> {
}
