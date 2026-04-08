package com.banka1.stock_service.dto;

/**
 * Response returned after seeding the built-in starter set of stock tickers.
 *
 * <p>The response mirrors the existing import summaries so startup logs can
 * consistently report how many predefined tickers resulted in new rows and
 * how many were already present.
 *
 * @param source human-readable label of the built-in seed source
 * @param processedRows number of predefined stock rows processed
 * @param createdCount number of tickers for which at least one missing row was created
 * @param updatedCount number of existing tickers updated during the run
 * @param unchangedCount number of predefined tickers that already had all required rows
 */
public record StockTickerSeedResponse(
        String source,
        int processedRows,
        int createdCount,
        int updatedCount,
        int unchangedCount
) {
}
