package com.banka1.order.entity.enums;

/**
 * Determines how the {@code value} field of a {@link com.banka1.order.entity.RecurringOrder}
 * is interpreted when the scheduler materializes the standing order into a Market Order.
 *
 * <p>Celina 3.6 (DCA / recurring orders): a standing order either buys/sells a fixed
 * <em>number of securities</em> or spends a fixed <em>amount of currency</em> regardless
 * of the current price (the dollar-cost-averaging case).
 */
public enum RecurringMode {

    /** {@code value} is a whole quantity of securities to buy or sell. */
    BY_QUANTITY,

    /**
     * {@code value} is an amount of currency to spend; the scheduler converts it to a
     * whole share quantity using the listing's ask price at run time. The DCA case.
     */
    BY_AMOUNT
}
