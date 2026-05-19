package com.banka1.order.dto;

import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for order creation and details.
 *
 * Contains comprehensive order information including type, quantity, pricing,
 * status, and execution details. Returned by order endpoints to provide clients
 * with complete order state.
 */
@Data
public class OrderResponse {
    /** Unique order identifier. */
    private Long id;

    /** ID of the user (client or agent) who placed the order. */
    private Long userId;

    /** ID of the security listing being traded. */
    private Long listingId;

    /** Ticker symbol of the traded security (e.g. "AAPL"); null if the listing cannot be resolved. */
    private String ticker;

    /** Full name of the traded security; null if the listing cannot be resolved. */
    private String securityName;

    /** Category of the traded security (STOCK, FUTURES, FOREX, OPTION); null if the listing cannot be resolved. */
    private ListingType listingType;

    /** Order type: MARKET, LIMIT, STOP, or STOP_LIMIT. */
    private OrderType orderType;

    /** Total quantity of securities in this order. */
    private Integer quantity;

    /** Number of units per contract for the listing. */
    private Integer contractSize;

    /** Reference price per unit (actual for MARKET, target for others). */
    private BigDecimal pricePerUnit;

    /** Activation price for LIMIT and STOP_LIMIT orders; null otherwise. */
    private BigDecimal limitValue;

    /** Stop price for STOP and STOP_LIMIT orders; null otherwise. */
    private BigDecimal stopValue;

    /** Direction of trade: BUY or SELL. */
    private OrderDirection direction;

    /** Current lifecycle status of the order. */
    private OrderStatus status;

    /** ID of the supervisor who approved this order; null if no approval needed. */
    private Long approvedBy;

    /** True when all portions of the order have been executed. */
    private Boolean isDone;

    /** Timestamp of the last status change (serves as the execution time for DONE orders). */
    private LocalDateTime lastModification;

    /** Timestamp of when the order was created; null for legacy orders without a recorded creation time. */
    private LocalDateTime createdAt;

    /** Number of units still awaiting execution. */
    private Integer remainingPortions;

    /** True if order was placed within 4 hours of exchange closing. */
    private Boolean afterHours;

    /** True if the exchange was closed when order was last validated. */
    private Boolean exchangeClosed;

    /** True if order must be filled completely or not at all (All-or-None). */
    private Boolean allOrNone;

    /** True if the order uses margin (borrowed funds). */
    private Boolean margin;

    /** ID of the account for settlement. */
    private Long accountId;

    /** Estimated total order value (quantity × pricePerUnit). */
    private BigDecimal approximatePrice;

    /** Calculated trading fee for this order. */
    private BigDecimal fee;
}
