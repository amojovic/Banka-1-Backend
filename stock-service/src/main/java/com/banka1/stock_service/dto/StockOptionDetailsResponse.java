package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.OptionType;

import java.math.BigDecimal;

/**
 * One option contract attached to a stock listing.
 *
 * @param id option identifier
 * @param ticker option ticker
 * @param optionType option direction
 * @param strikePrice strike price
 * @param impliedVolatility implied volatility as a decimal value
 * @param openInterest current open interest
 * @param inTheMoney indicator showing whether the option is currently in the money
 */
public record StockOptionDetailsResponse(
        Long id,
        String ticker,
        OptionType optionType,
        BigDecimal strikePrice,
        BigDecimal impliedVolatility,
        Integer openInterest,
        boolean inTheMoney
) {
}
