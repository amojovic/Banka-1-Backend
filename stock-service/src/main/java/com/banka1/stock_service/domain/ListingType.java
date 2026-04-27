package com.banka1.stock_service.domain;

/**
 * Category of security represented by one market listing snapshot.
 *
 * <p>The current listing model supports the three instrument groups required
 * by the specification.
 */
public enum ListingType {

    /**
     * Listing of a stock instrument.
     */
    STOCK,

    /**
     * Listing of a futures contract.
     */
    FUTURES,

    /**
     * Listing of an FX pair.
     */
    FOREX,

    /**
     * Listing of an options contract.
     */
    OPTION
}
