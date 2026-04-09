package com.banka1.stock_service.service;

import java.time.LocalDate;

/**
 * Abstraction for checking whether a local exchange date is a holiday for a polity.
 *
 * <p>The stock-exchange status logic depends only on this contract, not on a concrete
 * holiday source. That keeps the runtime logic stable while allowing a future switch
 * to a deterministic database- or seed-backed holiday implementation.
 */
public interface HolidayService {

    /**
     * Determines whether the provided local date should be treated as a holiday.
     *
     * @param polity exchange polity/country
     * @param date local exchange date
     * @return {@code true} when the date is a holiday for the given polity
     */
    boolean isHoliday(String polity, LocalDate date);
}
