package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.service.FundLiquidationService;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * REST endpoint koji SAGA orchestrator (FundRedeemWithLiquidationSaga step 1) poziva
 * preko TradingServiceClient-a (PR_15 C15.3).
 *
 * <p>Pre PR_15: saga je gadjala {@code POST /stocks/internal/funds/{fundId}/liquidate}
 * u market-service-u — endpoint nije postojao. Saga je padala u step 1 odmah.
 *
 * <p>Logika je u trading-service-u jer FundHolding entitet zivi ovde, ne u
 * market-service-u. Buduca integracija sa market-service-om za stvarno trgovanje
 * (umesto avgUnitPrice simulacije) je TBD u PR_16.
 */
@RestController
@RequestMapping("/funds/internal")
@RequiredArgsConstructor
public class FundLiquidationController {

    private final FundLiquidationService liquidationService;

    @PostMapping("/{fundId}/liquidate")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<FundLiquidationService.Result> liquidate(
            @PathVariable Long fundId,
            @RequestBody LiquidateRequest req,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
        return ResponseEntity.ok(liquidationService.liquidateForFund(
                fundId,
                req.getTargetAmount(),
                correlationId != null ? correlationId : "no-correlation"));
    }

    @Data
    public static class LiquidateRequest {
        @NotNull
        private BigDecimal targetAmount;
    }
}
