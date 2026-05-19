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
 *
 * <p>Every endpoint is JWT-secured and scoped to the caller. The owner of an
 * alert is the user identified by the JWT {@code id} claim; the
 * {@code recipientType} stored on a created alert is derived from the caller's
 * role — a {@code CLIENT_*} role maps to {@code CLIENT}, every other role to
 * {@code EMPLOYEE}.
 */
@RestController
@RequestMapping("/price-alerts")
@RequiredArgsConstructor
public class PriceAlertController {

    private static final String CLIENT_ROLE_PREFIX = "ROLE_CLIENT";
    private static final String RECIPIENT_TYPE_CLIENT = "CLIENT";
    private static final String RECIPIENT_TYPE_EMPLOYEE = "EMPLOYEE";

    private final PriceAlertService priceAlertService;

    /**
     * Returns the price alerts owned by the caller.
     *
     * @param authentication authenticated caller
     * @return the caller's alerts
     */
    @Operation(summary = "List the caller's price alerts")
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<List<PriceAlertDto>> getMyAlerts(Authentication authentication) {
        return ResponseEntity.ok(priceAlertService.getAlertsForUser(resolveUserId(authentication)));
    }

    /**
     * Creates a new price alert for the caller.
     *
     * @param request alert definition
     * @param authentication authenticated caller, used for both id and role
     * @return the created alert
     */
    @Operation(summary = "Create a price alert")
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
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

    /**
     * Toggles the {@code active} flag of one alert owned by the caller.
     *
     * @param id alert identifier
     * @param authentication authenticated caller
     * @return the updated alert
     */
    @Operation(summary = "Toggle a price alert active flag")
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<PriceAlertDto> toggleAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        return ResponseEntity.ok(priceAlertService.toggleAlert(resolveUserId(authentication), id));
    }

    /**
     * Deletes one alert owned by the caller.
     *
     * @param id alert identifier
     * @param authentication authenticated caller
     * @return empty {@code 204 NO_CONTENT} response
     */
    @Operation(summary = "Delete a price alert")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')")
    public ResponseEntity<Void> deleteAlert(
            @PathVariable Long id,
            Authentication authentication
    ) {
        priceAlertService.deleteAlert(resolveUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts the caller identifier from the JWT {@code id} claim.
     *
     * <p>The claim is accepted both as a JSON number and as a numeric string so
     * the endpoint tolerates the slight differences in how the issuing services
     * encode the {@code id} claim.
     *
     * @param authentication authenticated caller
     * @return caller identifier
     */
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

    /**
     * Derives the recipient identity space from the caller's granted roles.
     *
     * @param authentication authenticated caller
     * @return {@code CLIENT} when any {@code ROLE_CLIENT*} authority is present, otherwise {@code EMPLOYEE}
     */
    private String resolveRecipientType(Authentication authentication) {
        boolean isClient = authentication.getAuthorities().stream()
                .map(grantedAuthority -> grantedAuthority.getAuthority())
                .anyMatch(authority -> authority.startsWith(CLIENT_ROLE_PREFIX));
        return isClient ? RECIPIENT_TYPE_CLIENT : RECIPIENT_TYPE_EMPLOYEE;
    }

    /**
     * Returns the JWT principal of an authenticated caller.
     *
     * @param authentication authenticated caller
     * @return the JWT principal
     */
    private Jwt requireJwt(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication token.");
    }
}
