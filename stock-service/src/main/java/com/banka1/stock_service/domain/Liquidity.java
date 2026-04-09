package com.banka1.stock_service.domain;

/**
 * Liquidity classification used for FX pairs in the reference dataset.
 *
 * <p>The levels are intentionally coarse because the current domain model only needs
 * a stable categorical indicator for trading conditions and future risk calculations.
 */
public enum Liquidity {

    /**
     * Pair trades with high depth and typically tight spreads.
     */
    HIGH,

    /**
     * Pair trades regularly, but with less depth than the most liquid majors.
     */
    MEDIUM,

    /**
     * Pair trades with lower depth and potentially wider spreads.
     */
    LOW
}
