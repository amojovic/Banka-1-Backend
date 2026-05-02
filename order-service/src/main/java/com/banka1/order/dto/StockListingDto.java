package com.banka1.order.dto;

import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OptionType;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.Map;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * DTO representing a security listing as returned by the stock-service.
 */
@Data
public class StockListingDto {
    /** The listing's unique identifier. Stock-service serializes this field as "listingId". */
    @JsonProperty("listingId")
    private Long id;
    /** Ticker symbol (e.g. "AAPL", "MSFT"). */
    private String ticker;
    /** Full name of the security. */
    private String name;
    /** Last traded price. */
    private BigDecimal price;
    /** Ask price. */
    private BigDecimal ask;
    /** Bid price. */
    private BigDecimal bid;
    /** Currency code of the listing's exchange. Stored as-is from stock-service; getCurrency() normalizes to ISO code. */
    private String currency;

    private static final Map<String, String> CURRENCY_NAME_TO_ISO = Map.ofEntries(
        Map.entry("United States Dollar", "USD"),
        Map.entry("US Dollar", "USD"),
        Map.entry("Euro", "EUR"),
        Map.entry("British Pound", "GBP"),
        Map.entry("British Pound Sterling", "GBP"),
        Map.entry("Japanese Yen", "JPY"),
        Map.entry("Canadian Dollar", "CAD"),
        Map.entry("Australian Dollar", "AUD"),
        Map.entry("Swiss Franc", "CHF"),
        Map.entry("Serbian Dinar", "RSD")
    );

    public String getCurrency() {
        return currency == null ? null : CURRENCY_NAME_TO_ISO.getOrDefault(currency, currency);
    }
    /** Identifier of the exchange this listing belongs to. Stock-service serializes this field as "stockExchangeId". */
    @JsonProperty("stockExchangeId")
    private Long exchangeId;
    /** Number of units per contract. */
    private Integer contractSize;
    /** Listing category used for margin and portfolio handling. */
    private ListingType listingType;
    /** Current market volume available for simulation purposes. */
    private Long volume;
    /** Settlement date, when provided by stock-service. */
    private LocalDate settlementDate;
    /** Optional identifier of the underlying listing for option exercise. */
    private Long underlyingListingId;
    /** Optional strike price for option listings. */
    private BigDecimal strikePrice;
    /** Optional option type for option listings. */
    private OptionType optionType;
    /** Optional underlying spot price used for option margin calculation. */
    private BigDecimal underlyingPrice;
    /** Optional maintenance margin coming directly from stock-service. */
    private BigDecimal maintenanceMargin;
}
