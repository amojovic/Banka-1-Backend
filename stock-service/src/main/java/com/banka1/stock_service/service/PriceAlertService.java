package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.CreatePriceAlertRequest;
import com.banka1.stock_service.dto.PriceAlertDto;

import java.util.List;

/**
 * CRUD use cases for user-defined price alerts (Celina 3.2).
 *
 * <p>Every operation is scoped to a single caller identified by the JWT
 * {@code id} claim. Callers can never see or mutate another user's alerts.
 */
public interface PriceAlertService {

    /**
     * Returns all alerts owned by the caller, newest first.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @return the caller's alerts
     */
    List<PriceAlertDto> getAlertsForUser(Long userId);

    /**
     * Creates a new alert for the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param recipientType caller identity space — {@code CLIENT} or {@code EMPLOYEE}
     * @param request alert definition
     * @return the created alert
     */
    PriceAlertDto createAlert(Long userId, String recipientType, CreatePriceAlertRequest request);

    /**
     * Toggles the {@code active} flag of one alert owned by the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param alertId alert identifier
     * @return the updated alert
     */
    PriceAlertDto toggleAlert(Long userId, Long alertId);

    /**
     * Deletes one alert owned by the caller.
     *
     * @param userId caller identifier from the JWT {@code id} claim
     * @param alertId alert identifier
     */
    void deleteAlert(Long userId, Long alertId);
}
