package com.banka1.tradingservice.funds.controller;

import com.banka1.tradingservice.funds.domain.ClientFundPosition;
import com.banka1.tradingservice.funds.domain.ClientFundTransaction;
import com.banka1.tradingservice.funds.dto.CreateFundRequest;
import com.banka1.tradingservice.funds.dto.InvestmentFundDto;
import com.banka1.tradingservice.funds.dto.InvestmentRequest;
import com.banka1.tradingservice.funds.dto.RedemptionRequest;
import com.banka1.tradingservice.funds.service.InvestmentFundService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST kontroler za investicione fondove (PR_04).
 *
 * <p>Spec (Celina 4.txt):
 * <ul>
 *   <li>Supervizori (FUND_AGENT_MANAGE permission) kreiraju fondove i upravljaju njima.
 *   <li>Klijenti sa CLIENT_TRADING permisijom mogu da investiraju i povlace sredstva.
 * </ul>
 */
@RestController
@RequestMapping("/funds")
@RequiredArgsConstructor
public class InvestmentFundController {

    private final InvestmentFundService fundService;

    /** Discovery — svi mogu da vide listu fondova. */
    @GetMapping
    public ResponseEntity<List<InvestmentFundDto>> discovery() {
        return ResponseEntity.ok(fundService.discovery());
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvestmentFundDto> details(@PathVariable Long id) {
        return ResponseEntity.ok(fundService.details(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<InvestmentFundDto> createFund(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody @Valid CreateFundRequest req) {
        Long managerId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.createFund(req, managerId), HttpStatus.CREATED);
    }

    @PostMapping("/{id}/invest")
    @PreAuthorize("hasAuthority('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> invest(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid InvestmentRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.invest(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    @PostMapping("/{id}/redeem")
    @PreAuthorize("hasAuthority('CLIENT_TRADING')")
    public ResponseEntity<ClientFundTransaction> redeem(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("id") Long fundId,
            @RequestBody @Valid RedemptionRequest req) {
        Long clientId = jwt.getClaim("id");
        return new ResponseEntity<>(fundService.redeem(fundId, clientId, req), HttpStatus.ACCEPTED);
    }

    /** Moji fondovi (Moj portfolio -> Moji fondovi). */
    @GetMapping("/my-positions")
    public ResponseEntity<List<ClientFundPosition>> myPositions(@AuthenticationPrincipal Jwt jwt) {
        Long clientId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.myPositions(clientId));
    }

    /** Supervisor: fondovi koje on/ona upravlja. */
    @GetMapping("/supervised")
    @PreAuthorize("hasAuthority('FUND_AGENT_MANAGE')")
    public ResponseEntity<List<InvestmentFundDto>> supervised(@AuthenticationPrincipal Jwt jwt) {
        Long managerId = jwt.getClaim("id");
        return ResponseEntity.ok(fundService.supervisedBy(managerId));
    }
}
