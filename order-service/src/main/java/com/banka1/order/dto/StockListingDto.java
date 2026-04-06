package com.banka1.order.dto;

import com.banka1.order.entity.enums.OptionType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO representing a security listing as returned by the stock-service.
 */
@Data
public class StockListingDto {
    /** The listing's unique identifier. */
    private Long id;
    /** Ticker symbol (e.g. "AAPL", "MSFT"). */
    private String ticker;
    /** Full name of the security. */
    private String name;
    /** Last traded price. */
    private BigDecimal price;
    /** Currency code of the listing's exchange. */
    private String currency;
    /** Identifier of the exchange this listing belongs to. */
    private Long exchangeId;
    /** Number of units per contract. */
    private Integer contractSize;
    /**
     * Strike price of the option contract.
     * Price at which the underlying asset can be bought/sold.
     */
    private BigDecimal strikePrice;

    /**
     * Expiration / settlement date of the option contract.
     * Option can only be exercised before this date.
     */
    private LocalDateTime settlementDate;

    /**
     * Type of option:
     * CALL → profit if market price > strike price
     * PUT → profit if market price < strike price
     */
    private OptionType optionType;
}
