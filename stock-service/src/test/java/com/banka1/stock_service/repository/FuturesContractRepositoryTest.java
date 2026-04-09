package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.FuturesContract;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FuturesContractRepositoryTest {

    @Autowired
    private FuturesContractRepository futuresContractRepository;

    @Test
    void shouldPersistFuturesContractAndLoadItByTicker() {
        FuturesContract futuresContract = createContract(
                "BRENTNOV26",
                "Brent Oil Futures November 2026",
                1_000,
                "Barrel",
                LocalDate.of(2026, 11, 20)
        );
        futuresContractRepository.saveAndFlush(futuresContract);

        FuturesContract persisted = futuresContractRepository.findByTicker("BRENTNOV26").orElseThrow();

        assertTrue(persisted.getId() != null);
        assertEquals("Brent Oil Futures November 2026", persisted.getName());
        assertEquals(1_000, persisted.getContractSize());
        assertEquals("Barrel", persisted.getContractUnit());
        assertEquals(LocalDate.of(2026, 11, 20), persisted.getSettlementDate());
    }

    @Test
    void shouldEnforceUniqueTickerConstraint() {
        futuresContractRepository.saveAndFlush(
                createContract("CORNSEP26", "Corn Futures September 2026", 5_000, "Kilogram", LocalDate.of(2026, 9, 15))
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> futuresContractRepository.saveAndFlush(
                        createContract("CORNSEP26", "Duplicate Corn Contract", 10, "Kilogram", LocalDate.of(2026, 10, 15))
                )
        );
    }

    private FuturesContract createContract(
            String ticker,
            String name,
            int contractSize,
            String contractUnit,
            LocalDate settlementDate
    ) {
        FuturesContract futuresContract = new FuturesContract();
        futuresContract.setTicker(ticker);
        futuresContract.setName(name);
        futuresContract.setContractSize(contractSize);
        futuresContract.setContractUnit(contractUnit);
        futuresContract.setSettlementDate(settlementDate);
        return futuresContract;
    }
}
