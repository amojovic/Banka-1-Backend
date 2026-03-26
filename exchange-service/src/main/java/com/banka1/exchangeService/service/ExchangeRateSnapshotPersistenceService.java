package com.banka1.exchangeService.service;

import com.banka1.exchangeService.domain.ExchangeRateEntity;
import com.banka1.exchangeService.repository.ExchangeRateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Transakciona komponenta za atomsku zamenu celog dnevnog snapshot-a kurseva.
 */
@Service
@RequiredArgsConstructor
public class ExchangeRateSnapshotPersistenceService {

    private final ExchangeRateRepository exchangeRateRepository;

    /**
     * U jednoj transakciji zamenjuje ceo snapshot za zadati datum.
     *
     * @param snapshotDate datum snapshot-a koji se upisuje
     * @param rates        kompletno pripremljeni skup kurseva za taj datum
     * @return sacuvani entiteti sortirani po kodu valute
     */
    @Transactional
    public List<ExchangeRateEntity> replaceSnapshot(
            java.time.LocalDate snapshotDate,
            List<PreparedExchangeRate> rates
    ) {
        exchangeRateRepository.deleteByDate(snapshotDate);
        List<ExchangeRateEntity> entities = rates.stream()
                .map(rate -> toEntity(snapshotDate, rate))
                .toList();
        return exchangeRateRepository.saveAll(entities).stream()
                .sorted(java.util.Comparator.comparing(ExchangeRateEntity::getCurrencyCode))
                .toList();
    }

    private ExchangeRateEntity toEntity(java.time.LocalDate snapshotDate, PreparedExchangeRate rate) {
        ExchangeRateEntity entity = new ExchangeRateEntity();
        entity.setCurrencyCode(rate.currencyCode());
        entity.setBuyingRate(rate.buyingRate());
        entity.setSellingRate(rate.sellingRate());
        entity.setDate(snapshotDate);
        return entity;
    }

    /**
     * Interni model potpuno pripremljenog reda snapshot-a.
     *
     * @param currencyCode kod valute
     * @param buyingRate   kupovni kurs
     * @param sellingRate  prodajni kurs
     */
    public record PreparedExchangeRate(
            String currencyCode,
            BigDecimal buyingRate,
            BigDecimal sellingRate
    ) {
    }
}
