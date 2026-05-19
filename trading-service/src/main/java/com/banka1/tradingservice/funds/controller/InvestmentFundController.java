package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.domain.FundDividendPolicy;
import com.banka1.tradingservice.funds.dto.ClientFundPositionDto;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.FundHoldingDto;
import com.banka1.tradingservice.funds.dto.FundPerformancePointDto;
import com.banka1.tradingservice.funds.dto.FundStatisticsDto;
import com.banka1.tradingservice.funds.dto.FundValueSnapshotDto;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.ReassignManagerRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.service.FundLiquidationService;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import com.banka1.tradingservice.funds.service.InvestmentFundService.FundSortField;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class InvestmentFundController {

    private final InvestmentFundService fundService;
    private final FundLiquidationService fundLiquidationService;

    // -------------------- discovery --------------------

    /**
     * Discovery lista fondova. WP-18 (Celina 4.4): sortabilna po novim metrikama
     * statistike pored postojecih polja.
     *
     * @param sort      polje sortiranja: {@code naziv}, {@code totalValue},
     *                  {@code profit}, {@code minimumContribution},
     *                  {@code annualizedReturn}, {@code rewardToVariability},
     *                  {@code maxDrawdown}, {@code volatility}. Default {@code naziv}.
     * @param direction smer: {@code asc} (default) ili {@code desc}.
     * @return lista fondova (svaki DTO nosi metrike statistike; {@code null} kad
     *         fond nema dovoljno snapshot-a)
     */
    @GetMapping
    public ResponseEntity<List<InvestmentFundDto>> discovery(
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "direction", required = false, defaultValue = "asc") String direction) {
        FundSortField sortField = parseSortField(sort);
        boolean ascending = !"desc".equalsIgnoreCase(direction);
        return ResponseEntity.ok(fundService.discovery(sortField, ascending));
    }

    /**
     * Mapira {@code sort} query parametar (camelCase, frontend-friendly) na
     * {@link FundSortField}. Nepoznata/prazna vrednost -> {@code naziv}.
     */
    private static FundSortField parseSortField(String sort) {
        if (sort == null || sort.isBlank()) {
            return FundSortField.NAZIV;
        }
        return switch (sort.trim().toLowerCase()) {
            case "totalvalue", "value", "vrednost" -> FundSortField.TOTAL_VALUE;
            case "profit" -> FundSortField.PROFIT;
            case "minimumcontribution", "minimum" -> FundSortField.MINIMUM_CONTRIBUTION;
            case "annualizedreturn", "godisnjiprinos" -> FundSortField.ANNUALIZED_RETURN;
            case "rewardtovariability", "rewardtovariabilityratio", "sharpe" ->
                    FundSortField.REWARD_TO_VARIABILITY;
            case "maxdrawdown", "drawdown" -> FundSortField.MAX_DRAWDOWN;
            case "volatility", "volatilnost" -> FundSortField.VOLATILITY;
            default -> FundSortField.NAZIV;
        };
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestmentFundDto> details(@PathVariable Long id) {
        return ResponseEntity.ok(fundService.details(id));
    }

    /**
     * WP-18 (Celina 4.4): statistika performansi fonda — godisnji prinos,
     * reward-to-variability, max drawdown, volatilnost. Citljivo svakome ko vidi
     * fondove (kao discovery — bez {@code @PreAuthorize}).
     *
     * @param id ID fonda
     * @return {@link FundStatisticsDto}; metrike {@code null} ispod minimuma snapshot-a
     */
    @GetMapping("/{id}/statistics")
    public ResponseEntity<FundStatisticsDto> statistics(@PathVariable("id") Long id) {
        return ResponseEntity.ok(fundService.fundStatistics(id));
    }

    /**
     * WP-18 (Celina 4.4): stvarna serija istorijske vrednosti fonda — podaci za
     * grafikon vrednosti fonda. Citljivo svakome ko vidi fondove.
     *
     * @param id ID fonda
     * @return hronoloska serija {@link FundValueSnapshotDto}
     */
    @GetMapping("/{id}/value-history")
    public ResponseEntity<List<FundValueSnapshotDto>> valueHistory(@PathVariable("id") Long id) {
        return ResponseEntity.ok(fundService.fundValueHistory(id));
    }

    /**
     * WP-18 (Celina 4.4): sistemska prosecna serija — za poredbeni grafikon
     * (fond vs prosek svih fondova). Prosek {@code totalValue}-a po datumu preko
     * svih fondova. Citljivo svakome ko vidi fondove.
     *
     * @return hronoloska serija prosecnih tacaka ({@code fundId=null})
     */
    @GetMapping("/value-history/average")
    public ResponseEntity<List<FundValueSnapshotDto>> averageValueHistory() {
        return ResponseEntity.ok(fundService.averageValueHistory());
    }

    // -------------------- supervisor fund mgmt --------------------

    @PostMapping
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<InvestmentFundDto> createFund(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateFundRequest req) {
        Long managerId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.createFund(req, managerId), HttpStatus.CREATED);
    }

    @GetMapping("/supervised")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<InvestmentFundDto>> supervised(@AuthenticationPrincipal Jwt jwt) {
        Long managerId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.supervisedBy(managerId));
    }

    /**
     * WP-17 (Celina 4.3): supervizor menja politiku obrade dividende fonda.
     *
     * @param fundId ID fonda
     * @param req    nova politika ({@code REINVEST} ili {@code DISTRIBUTE})
     * @return azurirani fond
     */
    @PatchMapping("/{id}/dividend-policy")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<InvestmentFundDto> updateDividendPolicy(
            @PathVariable("id") Long fundId,
            @RequestBody @Valid DividendPolicyRequest req) {
        return ResponseEntity.ok(fundService.updateDividendPolicy(fundId, req.getDividendPolicy()));
    }

    @Data
    public static class DividendPolicyRequest {
        @NotNull
        private FundDividendPolicy dividendPolicy;
    }

    // -------------------- client invest/redeem --------------------

    @PostMapping("/{id}/invest")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> invest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid InvestmentRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.invest(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{id}/redeem")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> redeem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid RedemptionRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.redeem(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    @GetMapping("/my-positions")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<List<ClientFundPositionDto>> myPositions(@AuthenticationPrincipal Jwt jwt) {
        Long clientId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.myPositions(clientId));
    }

    // -------------------- bank invest/redeem (supervisor) --------------------

    @PostMapping("/{id}/bank-invest")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<ClientFundTransaction> bankInvest(
            @PathVariable("id") Long fundId,
            @RequestBody @Valid InvestmentRequest req) {
        return new ResponseEntity<>(fundService.bankInvest(fundId, req), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{id}/bank-redeem")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<ClientFundTransaction> bankRedeem(
            @PathVariable("id") Long fundId,
            @RequestBody @Valid RedemptionRequest req) {
        return new ResponseEntity<>(fundService.bankRedeem(fundId, req), HttpStatus.ACCEPTED);
    }

    @GetMapping("/bank-positions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundPositionDto>> bankPositions() {
        return ResponseEntity.ok(fundService.bankPositions());
    }

    // -------------------- fund holdings --------------------

    @GetMapping("/{id}/securities")
    public ResponseEntity<List<FundHoldingDto>> securities(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.getEnrichedHoldings(fundId));
    }

    @PostMapping("/{id}/securities/{ticker}/sell")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<FundLiquidationService.SellResult> sellSecurity(
            @PathVariable("id") Long fundId,
            @PathVariable("ticker") String ticker,
            @RequestBody @Valid SellRequest req) {
        return ResponseEntity.ok(fundLiquidationService.sellHolding(fundId, ticker, req.getQuantity()));
    }

    @Data
    public static class SellRequest {
        @NotNull
        @Positive
        private Integer quantity;
    }

    // -------------------- fund positions (supervisor view) --------------------

    @GetMapping("/{id}/positions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundPositionDto>> fundPositions(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundPositions(fundId));
    }

    // -------------------- transaction history --------------------

    @GetMapping("/my-transactions")
    @PreAuthorize("hasRole('CLIENT_TRADING')")
    public ResponseEntity<List<ClientFundTransaction>> myTransactions(@AuthenticationPrincipal Jwt jwt) {
        Long clientId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.myTransactions(clientId));
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<ClientFundTransaction>> fundTransactions(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundTransactions(fundId));
    }

    @GetMapping("/{id}/performance")
    public ResponseEntity<List<FundPerformancePointDto>> fundPerformance(@PathVariable("id") Long fundId) {
        return ResponseEntity.ok(fundService.fundPerformance(fundId));
    }

    // -------------------- admin --------------------

    @PatchMapping("/admin/reassign-manager")
    @PreAuthorize("hasRole('SERVICE')")
    public ResponseEntity<Void> reassignManager(@RequestBody @Valid ReassignManagerRequest req) {
        fundService.reassignManager(req.getOldManagerId(), req.getNewManagerId());
        return ResponseEntity.noContent().build();
    }
}
