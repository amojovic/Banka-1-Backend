package com.banka1.stock_service.dto;

/**
 * Response returned after importing futures contract reference data from a CSV source.
 *
 * <p>This mirrors the stock-exchange import summary so startup logs and future admin tooling
 * can clearly distinguish how many rows were created, updated, or skipped as unchanged.
 *
 * @param source CSV source location or label
 * @param processedRows number of parsed CSV rows
 * @param createdCount number of newly inserted futures contracts
 * @param updatedCount number of existing futures contracts updated from CSV
 * @param unchangedCount number of existing futures contracts already matching the CSV data
 */
public record FuturesContractImportResponse(
        String source,
        int processedRows,
        int createdCount,
        int updatedCount,
        int unchangedCount
) {
}
