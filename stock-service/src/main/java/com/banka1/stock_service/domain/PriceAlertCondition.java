package com.banka1.stock_service.domain;

/**
 * Trigger condition for a {@link PriceAlert}.
 */
public enum PriceAlertCondition {

    /** Fires when the current listing price rises to or above the threshold. */
    ABOVE,

    /** Fires when the current listing price falls to or below the threshold. */
    BELOW,

    /**
     * Fires when the listing's intraday percentage change has dropped by at
     * least {@code threshold} percent.
     */
    PCT_DROP_INTRADAY
}
