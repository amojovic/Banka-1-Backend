package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.OptionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Detailed response returned by {@code GET /api/listings/{id}}.
 *
 * @param listingId listing identifier
 * @param securityId underlying security identifier referenced by the listing
 * @param listingType listing category
 * @param ticker listing ticker
 * @param name listing display name
 * @param stockExchangeId exchange identifier
 * @param exchangeMICCode exchange MIC code
 * @param exchangeAcronym exchange acronym
 * @param exchangeName exchange display name
 * @param lastRefresh timestamp of the latest market snapshot
 * @param price current price
 * @param ask current ask price
 * @param bid current bid price
 * @param change absolute price change in the latest snapshot
 * @param changePercent derived percentage change in the latest snapshot
 * @param volume current traded volume
 * @param dollarVolume derived dollar volume in the latest snapshot
 * @param initialMarginCost derived initial margin cost for the listing type
 * @param requestedPeriod history window requested by the caller
 * @param priceHistory historical price rows used for chart rendering
 * @param stockDetails stock-specific fields when the listing is a stock
 * @param futuresDetails futures-specific fields when the listing is a futures contract
 * @param forexDetails FX-specific fields when the listing is an FX pair
 * @param optionGroups grouped stock options when the listing is a stock
 * @param currency trading currency of the listing's exchange (all types)
 * @param contractSize number of units per contract (all types)
 * @param maintenanceMargin derived maintenance margin (all types)
 * @param optionType CALL or PUT when the listing is an options contract
 * @param strikePrice agreed strike price when the listing is an options contract
 * @param underlyingListingId listing id of the underlying stock when the listing is an options contract
 * @param settlementDate expiration date when the listing is an options contract
 * @param underlyingPrice current price of the underlying stock when the listing is an options contract
 */
public record ListingDetailsResponse(
        Long listingId,
        Long securityId,
        ListingType listingType,
        String ticker,
        String name,
        Long stockExchangeId,
        String exchangeMICCode,
        String exchangeAcronym,
        String exchangeName,
        LocalDateTime lastRefresh,
        BigDecimal price,
        BigDecimal ask,
        BigDecimal bid,
        BigDecimal change,
        BigDecimal changePercent,
        Long volume,
        BigDecimal dollarVolume,
        BigDecimal initialMarginCost,
        ListingDetailsPeriod requestedPeriod,
        List<ListingDailyPriceInfoResponse> priceHistory,
        ListingStockDetailsResponse stockDetails,
        ListingFuturesDetailsResponse futuresDetails,
        ListingForexDetailsResponse forexDetails,
        List<StockOptionSettlementGroupResponse> optionGroups,
        String currency,
        Integer contractSize,
        BigDecimal maintenanceMargin,
        OptionType optionType,
        BigDecimal strikePrice,
        Long underlyingListingId,
        LocalDate settlementDate,
        BigDecimal underlyingPrice
) {
}
