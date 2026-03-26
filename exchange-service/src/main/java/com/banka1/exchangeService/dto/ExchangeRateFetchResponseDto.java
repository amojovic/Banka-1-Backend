package com.banka1.exchangeService.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO odgovora za rucni fetch kursne liste.
 * Najkrace:
 *   - ExchangeRateDto = jedan kurs
 *   - ExchangeRateFetchResponseDto = rezultat cele fetch operacije nad vise kurseva
 *
 * ExchangeRateFetchResponseDto postoji zato što imamo ručni fetch endpoint,
 * i taj endpoint radi praktično isto što i cron job:
 *   - cron job automatski pozove fetchAndStoreDailyRates()
 *   - ručni endpoint takođe pozove fetchAndStoreDailyRates()
 *
 * Ako FRONTEND zeli rucno da pozove refresh, a ne da ceka cron job, moze manualno da refreshuje, na zahtev
 *
 * @param fetchedCount       broj valuta koje su uspesno obradjene i sacuvane
 * @param rates              sacuvani kursevi
 * @param fallbackUsed       da li je odgovor nastao iz lokalnog fallback snapshot-a
 * @param sourceSnapshotDate datum snapshot-a iz kog su fallback podaci kopirani;
 *                           jednak je trenutnom snapshot datumu za svez provider fetch
 */
public record ExchangeRateFetchResponseDto(
        int fetchedCount,
        List<ExchangeRateDto> rates,
        boolean fallbackUsed,
        LocalDate sourceSnapshotDate
) {
}
