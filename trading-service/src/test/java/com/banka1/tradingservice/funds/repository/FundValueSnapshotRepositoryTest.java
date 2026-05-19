package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundValueSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP-18 (Celina 4.4): Spring Data slice test za {@link FundValueSnapshotRepository}.
 *
 * <p>H2 u PostgreSQL kompat. modu, Liquibase iskljucen — Hibernate gradi semu iz
 * {@link FundValueSnapshot} mapiranja. Verifikuje BIGSERIAL PK dodelu, UNIQUE
 * {@code (fund_id, snapshot_date)} constraint, hronolosko sortiranje serije
 * jednog fonda i celokupne serije.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FundValueSnapshotRepositoryTest {

    @Autowired
    private FundValueSnapshotRepository repository;

    private static FundValueSnapshot snapshot(Long fundId, LocalDate date, String totalValue, String profit) {
        return FundValueSnapshot.builder()
                .fundId(fundId)
                .snapshotDate(date)
                .totalValue(new BigDecimal(totalValue))
                .profit(new BigDecimal(profit))
                .build();
    }

    @Test
    void persistsAndAssignsIdentityPk() {
        FundValueSnapshot saved = repository.saveAndFlush(
                snapshot(1L, LocalDate.of(2026, 1, 1), "100000.0000", "0.0000"));

        assertNotNull(saved.getId(), "BIGSERIAL PK mora biti dodeljen");
        assertEquals(0, new BigDecimal("100000.0000").compareTo(saved.getTotalValue()));
    }

    @Test
    void enforcesUniqueFundDateConstraint() {
        repository.saveAndFlush(snapshot(7L, LocalDate.of(2026, 2, 1), "100000.0000", "5000.0000"));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(
                        snapshot(7L, LocalDate.of(2026, 2, 1), "200000.0000", "9000.0000")),
                "drugi snapshot za isti (fund_id, snapshot_date) mora pasti na UNIQUE constraint");
    }

    @Test
    void allowsSameDateForDifferentFunds() {
        repository.saveAndFlush(snapshot(10L, LocalDate.of(2026, 3, 1), "100000.0000", "0.0000"));
        repository.saveAndFlush(snapshot(11L, LocalDate.of(2026, 3, 1), "200000.0000", "0.0000"));

        assertEquals(1, repository.findByFundIdOrderBySnapshotDateAsc(10L).size());
        assertEquals(1, repository.findByFundIdOrderBySnapshotDateAsc(11L).size());
    }

    @Test
    void existsByFundIdAndSnapshotDate_reflectsPresence() {
        repository.saveAndFlush(snapshot(20L, LocalDate.of(2026, 4, 1), "100000.0000", "0.0000"));

        assertTrue(repository.existsByFundIdAndSnapshotDate(20L, LocalDate.of(2026, 4, 1)));
        assertFalse(repository.existsByFundIdAndSnapshotDate(20L, LocalDate.of(2026, 5, 1)));
        assertFalse(repository.existsByFundIdAndSnapshotDate(99L, LocalDate.of(2026, 4, 1)));
    }

    @Test
    void findByFundId_returnsChronologicalSeries_andDoesNotMixFunds() {
        // inserted out of order on purpose
        repository.saveAndFlush(snapshot(30L, LocalDate.of(2026, 3, 1), "120000.0000", "0.0000"));
        repository.saveAndFlush(snapshot(30L, LocalDate.of(2026, 1, 1), "100000.0000", "0.0000"));
        repository.saveAndFlush(snapshot(30L, LocalDate.of(2026, 2, 1), "110000.0000", "0.0000"));
        repository.saveAndFlush(snapshot(31L, LocalDate.of(2026, 1, 1), "999999.0000", "0.0000"));

        List<FundValueSnapshot> series = repository.findByFundIdOrderBySnapshotDateAsc(30L);

        assertEquals(3, series.size(), "samo snapshot-i fonda 30");
        assertEquals(LocalDate.of(2026, 1, 1), series.get(0).getSnapshotDate());
        assertEquals(LocalDate.of(2026, 2, 1), series.get(1).getSnapshotDate());
        assertEquals(LocalDate.of(2026, 3, 1), series.get(2).getSnapshotDate());
    }

    @Test
    void findAll_returnsChronologicalSeriesAcrossFunds() {
        repository.saveAndFlush(snapshot(40L, LocalDate.of(2026, 2, 1), "110000.0000", "0.0000"));
        repository.saveAndFlush(snapshot(41L, LocalDate.of(2026, 1, 1), "100000.0000", "0.0000"));

        List<FundValueSnapshot> all = repository.findAllByOrderBySnapshotDateAsc();

        assertEquals(2, all.size());
        assertEquals(LocalDate.of(2026, 1, 1), all.get(0).getSnapshotDate());
        assertEquals(LocalDate.of(2026, 2, 1), all.get(1).getSnapshotDate());
    }
}
