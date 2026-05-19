package com.banka1.order.controller;

import com.banka1.order.dto.CreateRecurringOrderRequest;
import com.banka1.order.dto.RecurringOrderDto;
import com.banka1.order.service.RecurringOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST controller for standing (recurring) orders — Celina 3.6 dollar-cost-averaging.
 *
 * <p>Every endpoint is scoped to the caller's JWT {@code id}; mutations enforce ownership
 * and a not-owned standing-order id resolves to 404.
 */
@RestController
@RequestMapping("/recurring-orders")
@RequiredArgsConstructor
public class RecurringOrderController {

    private final RecurringOrderService recurringOrderService;

    /**
     * Lists the caller's standing orders.
     *
     * @param jwt the authenticated user
     * @return the caller's standing orders
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<List<RecurringOrderDto>> getMyRecurringOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(recurringOrderService.getForUser(callerId(jwt)));
    }

    /**
     * Creates a new standing order for the caller.
     *
     * @param jwt     the authenticated user
     * @param request the standing-order parameters
     * @return the created standing order
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> createRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                  @Valid @RequestBody CreateRecurringOrderRequest request) {
        RecurringOrderDto created = recurringOrderService.create(callerId(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Pauses one of the caller's standing orders.
     *
     * @param jwt the authenticated user
     * @param id  the standing order to pause
     * @return the updated standing order
     */
    @PatchMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> pauseRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                 @PathVariable Long id) {
        return ResponseEntity.ok(recurringOrderService.pause(callerId(jwt), id));
    }

    /**
     * Resumes one of the caller's paused standing orders.
     *
     * @param jwt the authenticated user
     * @param id  the standing order to resume
     * @return the updated standing order
     */
    @PatchMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> resumeRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable Long id) {
        return ResponseEntity.ok(recurringOrderService.resume(callerId(jwt), id));
    }

    /**
     * Cancels (deletes) one of the caller's standing orders.
     *
     * @param jwt the authenticated user
     * @param id  the standing order to cancel
     * @return an empty 204 response
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<Void> cancelRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable Long id) {
        recurringOrderService.cancel(callerId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts the caller's numeric user id from the JWT. The {@code id} claim carries
     * the numeric identifier; the {@code sub} claim is the fallback.
     *
     * @param jwt the authenticated user's JWT
     * @return the caller's user id
     */
    private Long callerId(Jwt jwt) {
        Object idClaim = jwt.getClaim("id");
        if (idClaim instanceof Number number) {
            return number.longValue();
        }
        return Long.valueOf(jwt.getSubject());
    }
}
