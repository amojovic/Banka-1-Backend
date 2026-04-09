package com.banka1.stock_service.dto;

/**
 * Response returned after importing stock exchange reference data from a CSV source.
 *
 * @param source CSV source location or label
 * @param processedRows number of parsed CSV rows
 * @param createdCount number of newly inserted exchanges
 * @param updatedCount number of existing exchanges updated from CSV
 * @param unchangedCount number of existing exchanges already matching the CSV data
 */
public record StockExchangeImportResponse(
        String source,
        int processedRows,
        int createdCount,
        int updatedCount,
        int unchangedCount
) {
}
