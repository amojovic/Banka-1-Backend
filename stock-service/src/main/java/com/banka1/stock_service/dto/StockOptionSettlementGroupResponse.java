package com.banka1.stock_service.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Group of stock options sharing the same settlement date.
 *
 * @param settlementDate common option settlement date
 * @param calls call options for the date
 * @param puts put options for the date
 */
public record StockOptionSettlementGroupResponse(
        LocalDate settlementDate,
        List<StockOptionDetailsResponse> calls,
        List<StockOptionDetailsResponse> puts
) {
}
