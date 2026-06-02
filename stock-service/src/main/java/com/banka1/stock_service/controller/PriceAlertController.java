package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.CreatePriceAlertRequest;
import com.banka1.stock_service.dto.PriceAlertDto;
import com.banka1.stock_service.service.PriceAlertService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Price alert API (Celina 3.2).
 */
@RestController
@RequestMapping("/price-alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private static final String CLIENT_ROLE_PREFIX = "ROLE_CLIENT";
    private static final String RECIPIENT_TYPE_CLIENT = "CLIENT";
    private static final String RECIPIENT_TYPE_EMPLOYEE = "EMPLOYEE";

    private final PriceAlertService priceAlertService;

    @Operation(summary = "List the caller's price alerts")
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'CLIENT_TRADING', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<List<PriceAlertDto>> getMyAlerts(Authentication authentication) {
        return ResponseEntity.ok(priceAlertService.getAlertsForUser(resolveUserId(authentication)));
    }

    @Operation(summary = "Create a price alert")
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'CLIENT_TRADING', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<PriceAlertDto> createAlert(
            @Valid @RequestBody CreatePriceAlertRequest request,
            Authentication authentication
    ) {
        PriceAlertDto created = priceAlertService.createAlert(
                resolveUserId(authentication),
                resolveRecipientType(authentication),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Toggle a price alert active flag")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'CLIENT_TRADING', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<PriceAlertDto> toggleAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(priceAlertService.toggleAlert(resolveUserId(authentication), id));
    }

    @Operation(summary = "Delete a price alert")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'CLIENT_TRADING', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<Void> deleteAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        priceAlertService.deleteAlert(resolveUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private Long resolveUserId(Authentication authentication) {
        Jwt jwt = requireJwt(authentication);
        Object idClaim = jwt.getClaim("id");
        if (idClaim instanceof Number number) {
            return number.longValue();
        }
        if (idClaim instanceof String stringId && !stringId.isBlank()) {
            try {
                return Long.parseLong(stringId.trim());
            } catch (NumberFormatException numberFormatException) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Token id claim is not a valid identifier.");
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token is missing the id claim.");
    }

    private String resolveRecipientType(Authentication authentication) {
        boolean isClient = authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .anyMatch(authority -> authority.startsWith(CLIENT_ROLE_PREFIX));
        return isClient ? RECIPIENT_TYPE_CLIENT : RECIPIENT_TYPE_EMPLOYEE;
    }

    private Jwt requireJwt(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication token.");
    }
}
