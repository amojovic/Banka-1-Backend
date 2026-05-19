package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * WP-18 (Celina 4.4): Spring Data repozitorijum nad {@link FundValueSnapshot}.
 *
 * <p>{@code FundSnapshotScheduler} koristi {@link #existsByFundIdAndSnapshotDate}
 * za idempotenciju (ne dupliraj snapshot za isti fond+datum).
 * {@code FundStatisticsService} koristi {@link #findByFundIdOrderBySnapshotDateAsc}
 * — serija jednog fonda, hronoloski, ulaz za obracun metrika.
 * {@link #findAllByOrderBySnapshotDateAsc} daje sve snapshot-e (svih fondova) za
 * sistemski prosek (poredbeni grafikon).
 */
@Repository
public interface FundValueSnapshotRepository extends JpaRepository<FundValueSnapshot, Long> {

    /** Idempotencija scheduler-a: postoji li vec snapshot za par (fond, datum). */
    boolean existsByFundIdAndSnapshotDate(Long fundId, LocalDate snapshotDate);

    /** Serija snapshot-a jednog fonda, najstariji prvi — ulaz za obracun statistike. */
    List<FundValueSnapshot> findByFundIdOrderBySnapshotDateAsc(Long fundId);

    /** Svi snapshot-i svih fondova, najstariji prvi — za sistemski prosek. */
    List<FundValueSnapshot> findAllByOrderBySnapshotDateAsc();
}
