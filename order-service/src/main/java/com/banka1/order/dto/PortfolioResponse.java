package com.banka1.order.dto;

import com.banka1.order.entity.enums.ListingType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO representing a user's portfolio position returned by the API.
 *
 * Used by the frontend to display:
 * <ul>
 *   <li>Current holdings and quantities</li>
 *   <li>Market value and current price</li>
 *   <li>Profit/loss information (unrealized or realized)</li>
 *   <li>OTC visibility and public quantity (for stocks)</li>
 *   <li>Option exercise status and availability</li>
 * </ul>
 *
 * Notes:
 * <ul>
 *   <li>currentPrice is computed at runtime from stock-service</li>
 *   <li>profit is calculated based on quantity, average price, and current price</li>
 *   <li>publicQuantity and exercisable are null for non-applicable listing types</li>
 *   <li>All monetary values are in the listing's native currency or RSD</li>
 * </ul>
 */
@Data
public class PortfolioResponse {

    /**
     * Database primary key of the Portfolio entity. Required by the frontend
     * to address a specific position (e.g. exercising an option, setting a
     * public OTC quantity, or initiating a SELL flow that needs to identify
     * the exact holding row).
     */
    private Long id;

    /**
     * Listing identifier of the underlying security. Without this field the
     * portfolio list does not carry enough information to navigate to a
     * direction-specific Create Order route, which is the entry point for
     * selling a held position.
     */
    private Long listingId;

    /** Type of security held: STOCK, FUTURES, FOREX, or OPTION. */
    private ListingType listingType;

    /**
     * Ticker symbol of the security (e.g. "AAPL", "MSFT", "EURUSD").
     * Fetched from stock-service based on listingId.
     */
    private String ticker;

    /** Number of units currently held (accounting for reserved quantities). */
    private Integer quantity;

    /** Number of units available for public OTC trading. Only meaningful for STOCK listings; otherwise zero. */
    private Integer publicQuantity;

    /** Whether the option can currently be exercised (in-the-money and not expired). Null for non-option holdings. */
    private Boolean exercisable;

    /** Timestamp of when this portfolio position was last modified. */
    private LocalDateTime lastModified;

    /** Current market price for this security fetched from stock-service. In the security's native currency. */
    private BigDecimal currentPrice;

    /** Weighted average price at which units were purchased. In the security's native currency. */
    private BigDecimal averagePurchasePrice;

    /** Unrealized profit/loss for this position. Calculated as: (currentPrice - averagePurchasePrice) × quantity. */
    private BigDecimal profit;

}
