package com.banka1.stock_service.dto;

import java.math.BigDecimal;

/**
 * WP-14 (Celina 3.7): interni DTO sa podacima koji su trading-service-u potrebni
 * za kvartalni obracun dividende.
 *
 * <p>Vraca ga {@code GET /stocks/internal/dividend-data}, po jednom redu za svaku
 * STOCK listing snapshot u sistemu. Trading-service nema pristup
 * {@code stock}/{@code listing}/{@code stock_exchange} tabelama (one zive u
 * konsolidovanom market-service-u), pa ova projekcija spaja:
 * <ul>
 *   <li>{@code listingId} + {@code price} + {@code currency} iz {@code listing}
 *       (cena na dan obracuna, valuta = valuta berze na kojoj je listing);</li>
 *   <li>{@code ticker} + {@code dividendYield} iz {@code stock} entiteta.</li>
 * </ul>
 *
 * @param listingId     identifikator listing snapshot-a u stock-service-u
 * @param ticker        ticker hartije (npr. {@code AAPL})
 * @param price         poslednja cena listinga, u valuti berze
 * @param currency      valuta berze na kojoj je hartija listirana (npr. {@code USD})
 * @param dividendYield dividendna stopa hartije, kao decimalna vrednost
 */
public record StockDividendDataResponse(
        Long listingId,
        String ticker,
        BigDecimal price,
        String currency,
        BigDecimal dividendYield
) {
}
