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
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/recurring-orders")
@RequiredArgsConstructor
public class RecurringOrderController {

    private final RecurringOrderService recurringOrderService;

    @GetMapping
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<List<RecurringOrderDto>> getMyRecurringOrders(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(recurringOrderService.getForUser(callerId(jwt)));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> createRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                  @Valid @RequestBody CreateRecurringOrderRequest request) {
        RecurringOrderDto created = recurringOrderService.create(callerId(jwt), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PatchMapping("/{id}/pause")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> pauseRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                 @PathVariable Long id) {
        return ResponseEntity.ok(recurringOrderService.pause(callerId(jwt), id));
    }

    @PatchMapping("/{id}/resume")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<RecurringOrderDto> resumeRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                                  @PathVariable Long id) {
        return ResponseEntity.ok(recurringOrderService.resume(callerId(jwt), id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<Void> cancelRecurringOrder(@AuthenticationPrincipal Jwt jwt,
                                                     @PathVariable Long id) {
        recurringOrderService.cancel(callerId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private Long callerId(Jwt jwt) {
        Object idClaim = jwt.getClaim("id");
        if (idClaim instanceof Number number) {
            return number.longValue();
        }
        // `id` claim missing/non-numeric: fall back to `sub`, but only if it is a
        // numeric id. A username/UUID subject must not blow up with NumberFormatException
        // (-> 500); surface a controlled 401 instead.
        String subject = jwt.getSubject();
        if (subject != null && subject.matches("\\d+")) {
            return Long.valueOf(subject);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT is missing a numeric 'id' claim");
    }
}
