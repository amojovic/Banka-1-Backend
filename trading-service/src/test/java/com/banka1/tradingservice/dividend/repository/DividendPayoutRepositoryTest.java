package com.banka1.tradingservice.dividend.repository;

import com.banka1.tradingservice.dividend.domain.DividendPayout;
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
 * WP-14: Spring Data slice test za {@link DividendPayoutRepository}.
 *
 * <p>H2 u PostgreSQL kompat. modu, Liquibase iskljucen — Hibernate gradi semu
 * iz {@link DividendPayout} mapiranja.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DividendPayoutRepositoryTest {

    @Autowired
    private DividendPayoutRepository repository;

    private DividendPayout payout(Long userId, Long listingId, LocalDate date, boolean forBank) {
        return DividendPayout.builder()
                .userId(userId)
                .stockTicker("AAPL")
                .listingId(listingId)
                .quantity(10)
                .grossAmount(new BigDecimal("12.5000"))
                .currency("USD")
                .taxAmountRsd(forBank ? BigDecimal.ZERO : new BigDecimal("20.0000"))
                .netAmount(new BigDecimal("12.3000"))
                .accountId(500L)
                .paymentDate(date)
                .forBank(forBank)
                .build();
    }

    @Test
    void persistsAndAssignsId() {
        DividendPayout saved = repository.saveAndFlush(
                payout(7L, 1L, LocalDate.of(2026, 3, 31), false));

        assertNotNull(saved.getId(), "BIGSERIAL PK mora biti dodeljen");
        assertEquals(7L, repository.findById(saved.getId()).orElseThrow().getUserId());
    }

    @Test
    void prePersistFillsDefaultsWhenNull() {
        DividendPayout entity = payout(1L, 1L, LocalDate.of(2026, 6, 30), false);
        entity.setTaxAmountRsd(null);

        DividendPayout saved = repository.saveAndFlush(entity);

        assertNotNull(saved.getTaxAmountRsd(), "@PrePersist mora popuniti taxAmountRsd kad je null");
        assertEquals(0, BigDecimal.ZERO.compareTo(saved.getTaxAmountRsd()));
    }

    @Test
    void uniqueConstraintRejectsDuplicateUserListingDateForBank() {
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false));

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false)),
                "duplikat (user, listing, datum, for_bank=false) mora pasti na unique constraint");
    }

    @Test
    void uniqueConstraintAllowsPersonalAndBankRowSameDateSameListing() {
        // Isti drzalac, isti listing, isti datum — ali razlicit for_bank — DOZVOLJENO
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false));
        DividendPayout bank = repository.saveAndFlush(
                payout(7L, 1L, LocalDate.of(2026, 3, 31), true));

        assertNotNull(bank.getId(), "bank-held red pored licnog mora biti dozvoljen");
    }

    @Test
    void existsByForBankFalseReportsPresence() {
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false));

        assertTrue(repository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                7L, 1L, LocalDate.of(2026, 3, 31), false));
        assertFalse(repository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                7L, 1L, LocalDate.of(2026, 3, 31), true),
                "bank-held red jos nije dodat — mora biti false");
        assertFalse(repository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                7L, 1L, LocalDate.of(2026, 6, 30), false));
    }

    @Test
    void existsByForBankTrueReportsPresence() {
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), true));

        assertTrue(repository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                7L, 1L, LocalDate.of(2026, 3, 31), true));
        assertFalse(repository.existsByUserIdAndListingIdAndPaymentDateAndForBank(
                7L, 1L, LocalDate.of(2026, 3, 31), false),
                "licni red jos nije dodat — mora biti false");
    }

    @Test
    void findByUserIdReturnsNewestFirst() {
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false));
        repository.saveAndFlush(payout(7L, 2L, LocalDate.of(2026, 9, 30), false));
        repository.saveAndFlush(payout(7L, 3L, LocalDate.of(2026, 6, 30), false));
        repository.saveAndFlush(payout(8L, 4L, LocalDate.of(2026, 12, 31), false));

        List<DividendPayout> history = repository.findByUserIdOrderByPaymentDateDesc(7L);

        assertEquals(3, history.size(), "samo isplate korisnika 7");
        assertEquals(LocalDate.of(2026, 9, 30), history.get(0).getPaymentDate());
        assertEquals(LocalDate.of(2026, 6, 30), history.get(1).getPaymentDate());
        assertEquals(LocalDate.of(2026, 3, 31), history.get(2).getPaymentDate());
    }

    @Test
    void findByUserIdAndListingIdFiltersToOnePosition() {
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), false));
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 3, 31), true));
        repository.saveAndFlush(payout(7L, 1L, LocalDate.of(2026, 6, 30), false));
        repository.saveAndFlush(payout(7L, 2L, LocalDate.of(2026, 6, 30), false));

        List<DividendPayout> history =
                repository.findByUserIdAndListingIdOrderByPaymentDateDesc(7L, 1L);

        assertEquals(3, history.size(), "licna i bank-held za listing 1 zajedno");
        assertEquals(LocalDate.of(2026, 6, 30), history.get(0).getPaymentDate());
    }
}
