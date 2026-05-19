package com.banka1.tradingservice.funds.repository;

import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.domain.InvestmentFund;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * WP-17 (Celina 4.3): Spring Data slice test za {@code InvestmentFund.dividendPolicy}.
 *
 * <p>H2 u PostgreSQL kompat. modu, Liquibase iskljucen — Hibernate gradi semu
 * iz {@link InvestmentFund} mapiranja. Verifikuje da je novo polje
 * {@code dividend_policy} mapirano kao {@code VARCHAR}/{@code EnumType.STRING},
 * da je default {@link FundDividendPolicy#REINVEST} i da round-trip cuva
 * eksplicitno postavljenu vrednost.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InvestmentFundDividendPolicyTest {

    @Autowired
    private InvestmentFundRepository repository;

    private InvestmentFund fund(String accountNumber) {
        InvestmentFund f = new InvestmentFund();
        f.setNaziv("Alpha Growth");
        f.setOpis("Tech fund");
        f.setMinimumContribution(new BigDecimal("1000.00"));
        f.setManagerId(50L);
        f.setLikvidnaSredstva(new BigDecimal("100000.00"));
        f.setAccountNumber(accountNumber);
        return f;
    }

    @Test
    void newFundDefaultsToReinvestPolicy() {
        InvestmentFund saved = repository.saveAndFlush(fund("1111111111111111"));

        assertNotNull(saved.getId(), "IDENTITY PK mora biti dodeljen");
        assertEquals(FundDividendPolicy.REINVEST, saved.getDividendPolicy(),
                "novi fond bez eksplicitne politike -> REINVEST default");
    }

    @Test
    void persistsAndReadsBackExplicitDistributePolicy() {
        InvestmentFund f = fund("2222222222222222");
        f.setDividendPolicy(FundDividendPolicy.DISTRIBUTE);

        Long id = repository.saveAndFlush(f).getId();
        repository.flush();

        InvestmentFund reloaded = repository.findById(id).orElseThrow();
        assertEquals(FundDividendPolicy.DISTRIBUTE, reloaded.getDividendPolicy(),
                "EnumType.STRING round-trip mora sacuvati DISTRIBUTE");
    }
}
