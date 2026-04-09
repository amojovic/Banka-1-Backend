package com.banka1.stock_service.service;

import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Temporary holiday stub used until a deterministic holiday calendar is introduced.
 *
 * <p>This implementation deliberately reports no holidays. Current exchange-status
 * calculations therefore rely on timezone conversion, weekend detection, market session
 * windows, and the active-toggle override only.
 */
@Service
public class NoOpHolidayService implements HolidayService {

    @Override
    public boolean isHoliday(String polity, LocalDate date) {
        return false;
    }
}
