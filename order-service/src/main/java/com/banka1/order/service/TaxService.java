package com.banka1.order.service;

/**
 * Service responsible for calculating and collecting capital gains tax.
 */
public interface TaxService {

    /**
     * Calculate and collect taxes for the previous calendar month.
     */
    void collectMonthlyTax();
}

