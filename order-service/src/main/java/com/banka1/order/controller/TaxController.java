package com.banka1.order.controller;

import com.banka1.order.service.TaxService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller responsible for handling capital gains tax operations.
 *
 * <p>
 * This controller exposes endpoints for:
 * <ul>
 *     <li>Manual triggering of monthly capital gains tax calculation</li>
 *     <li>Fetching user tax debts (aggregated in RSD)</li>
 * </ul>
 *
 * <p>
 * There are two types of endpoints:
 * <ul>
 *     <li><b>/api/...</b> – intended for UI (supervisor portal)</li>
 *     <li><b>/internal/...</b> – intended for internal system communication</li>
 * </ul>
 *
 * <p>
 * Tax rules:
 * <ul>
 *     <li>Tax rate: 15% of capital gain</li>
 *     <li>Capital gain = selling price - buying price</li>
 *     <li>Only positive gains are taxed</li>
 *     <li>All amounts are converted to RSD before transfer</li>
 * </ul>
 */
@RestController
@RequestMapping
public class TaxController {

    private final TaxService taxService;

    public TaxController(TaxService taxService) {
        this.taxService = taxService;
    }

    /**
     * Manually triggers monthly capital gains tax calculation.
     *
     * <p>
     * Intended for supervisor usage via portal.
     * This endpoint calculates tax for all eligible transactions,
     * performs currency conversion to RSD, and initiates transfers
     * from user accounts to the state account.
     *
     * @return HTTP 200 if successfully triggered
     */
    @PostMapping("/api/tax/capital-gains/run")
    public ResponseEntity<Void> runTaxCalculation() {
        taxService.collectMonthlyTax();
        return ResponseEntity.ok().build();
    }

    /**
     * Internal endpoint for triggering capital gains tax calculation.
     *
     * <p>
     * Used by other backend services or for internal orchestration.
     * Same logic as public API endpoint but without UI/security concerns.
     *
     * @return HTTP 200 if successfully triggered
     */
    @PostMapping("/internal/tax/capital-gains/run")
    public ResponseEntity<Void> runTaxCalculationInternal() {
        taxService.collectMonthlyTax();
        return ResponseEntity.ok().build();
    }

    /**
     * Retrieves a list of all users with their current tax debts.
     *
     * <p>
     * Debt is calculated based on the latest tax computation and
     * returned in RSD. If users have accounts in multiple currencies,
     * values are converted using exchange service (without commission).
     *
     * @return list of user tax debts
     */
    @GetMapping("/api/tax/capital-gains/debts")
    public ResponseEntity<List<com.banka1.order.dto.TaxDebtResponse>> getAllDebts() {
        return ResponseEntity.ok(taxService.getAllDebts());
    }

    /**
     * Retrieves tax debt for a specific user.
     *
     * @param userId ID of the user
     * @return tax debt in RSD
     */
    @GetMapping("/api/tax/capital-gains/{userId}")
    public ResponseEntity<com.banka1.order.dto.TaxDebtResponse> getUserDebt(@PathVariable Long userId) {
        return ResponseEntity.ok(taxService.getUserDebt(userId));
    }
}