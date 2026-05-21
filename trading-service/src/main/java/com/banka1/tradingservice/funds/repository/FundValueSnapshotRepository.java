package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FundValueSnapshotRepository extends JpaRepository<FundValueSnapshot, Long> {

    Optional<FundValueSnapshot> findByFundIdAndSnapshotDate(Long fundId, LocalDate snapshotDate);

    List<FundValueSnapshot> findByFundIdOrderBySnapshotDateAsc(Long fundId);

    List<FundValueSnapshot> findBySnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(LocalDate snapshotDate);
}
