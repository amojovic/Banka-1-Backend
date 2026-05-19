package com.banka1.stock_service.domain;

/**
 * Trigger condition for a {@link PriceAlert}.
 *
 * <p>Celina 3.2 lets a user be notified when a tracked security reaches a price
 * target. The condition describes <em>how</em> the configured {@code threshold}
 * is compared against the current listing price during an evaluation pass.
 */
public enum PriceAlertCondition {

    /**
     * Fires when the current listing price rises to or above the threshold
     * (e.g. "notify me when AAPL crosses $200").
     */
    ABOVE,

    /**
     * Fires when the current listing price falls to or below the threshold.
     */
    BELOW,

    /**
     * Fires when the listing's intraday percentage change has dropped by at
     * least {@code threshold} percent (e.g. "notify me on a 5% intraday drop").
     * The threshold is expressed as a positive percentage magnitude.
     */
    PCT_DROP_INTRADAY
}
