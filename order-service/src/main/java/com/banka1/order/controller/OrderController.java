package com.banka1.order.controller;

import com.banka1.order.dto.AuthenticatedUser;
import com.banka1.order.dto.CreateBuyOrderRequest;
import com.banka1.order.dto.CreateSellOrderRequest;
import com.banka1.order.dto.MyOrdersFilter;
import com.banka1.order.dto.OrderOverviewResponse;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.dto.PartialCancelOrderRequest;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderOverviewStatusFilter;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.service.OrderCreationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * REST controller for managing brokerage orders.
 */
@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCreationService orderCreationService;

    /**
     * Creates a new buy order.
     *
     * @param jwt the authenticated user
     * @param request the buy order details
     * @return the created order response
     */
    @PostMapping("/buy")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<OrderResponse> createBuyOrder(@AuthenticationPrincipal Jwt jwt,
                                                        @Valid @RequestBody CreateBuyOrderRequest request) {
        OrderResponse response = orderCreationService.createBuyOrder(toAuthenticatedUser(jwt), request);
        return ResponseEntity.ok(response);
    }

    /**
     * Creates a new sell order.
     *
     * @param jwt the authenticated user
     * @param request the sell order details
     * @return the created order response
     */
    @PostMapping("/sell")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<OrderResponse> createSellOrder(@AuthenticationPrincipal Jwt jwt,
                                                         @Valid @RequestBody CreateSellOrderRequest request) {
        OrderResponse response = orderCreationService.createSellOrder(toAuthenticatedUser(jwt), request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<Page<OrderOverviewResponse>> getOrders(
            @RequestParam(defaultValue = "ALL") OrderOverviewStatusFilter status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size
    ) {
        return ResponseEntity.ok(orderCreationService.getOrders(status, PageRequest.of(page, size)));
    }

    /**
     * Returns the authenticated user's own order history ("Moji orderi"), with optional
     * filtering and paging. Available to every trading user — clients and agents (actuaries)
     * alike; the result is always scoped to the caller's own orders.
     *
     * @param jwt          the authenticated user
     * @param status       optional order status filter
     * @param direction    optional BUY/SELL filter
     * @param securityType optional security category filter (STOCK, FUTURES, FOREX, OPTION)
     * @param from         optional inclusive lower bound on the order creation date
     * @param to           optional inclusive upper bound on the order creation date
     * @param page         zero-based page index
     * @param size         page size
     * @return a page of the caller's orders, each carrying the paid commission
     */
    @GetMapping("/my-orders")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC','CLIENT_TRADING','CLIENT','AGENT','SUPERVISOR')")
    public ResponseEntity<PagedModel<OrderResponse>> getMyOrders(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) OrderDirection direction,
            @RequestParam(required = false) ListingType securityType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        // Date bounds arrive as plain dates; widen them to cover the whole inclusive day.
        LocalDateTime fromTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toTime = to == null ? null : to.atTime(java.time.LocalTime.MAX);
        MyOrdersFilter filter = new MyOrdersFilter(status, direction, securityType, fromTime, toTime);
        // Newest-first; legacy orders without a creation timestamp sort last.
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by(Sort.Order.desc("createdAt").nullsLast()));
        Page<OrderResponse> result = orderCreationService.getMyOrders(toAuthenticatedUser(jwt), filter, pageable);
        // PagedModel is the stable, module-free JSON representation of a Page
        // ({content, page:{size,number,totalElements,totalPages}}).
        return ResponseEntity.ok(new PagedModel<>(result));
    }

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<OrderResponse> confirmOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(orderCreationService.confirmOrder(toAuthenticatedUser(jwt), id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('CLIENT_TRADING','AGENT','SUPERVISOR')")
    public ResponseEntity<OrderResponse> cancelOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(orderCreationService.cancelOrder(toAuthenticatedUser(jwt), id));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable Long id,
            @RequestBody(required = false) PartialCancelOrderRequest request
    ) {
        return ResponseEntity.ok(orderCreationService.cancelOrder(id, request == null ? null : request.getQuantity()));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<OrderResponse> approveOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(orderCreationService.approveOrder(toAuthenticatedUser(jwt).userId(), id));
    }

    @PutMapping("/{id}/decline")
    @PreAuthorize("hasRole('SUPERVISOR')")
    public ResponseEntity<OrderResponse> declineOrder(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return ResponseEntity.ok(orderCreationService.declineOrder(toAuthenticatedUser(jwt).userId(), id));
    }

    private AuthenticatedUser toAuthenticatedUser(Jwt jwt) {
        Object idClaim = jwt.getClaim("id");
        Long id = idClaim != null
                ? ((Number) idClaim).longValue()
                : Long.valueOf(jwt.getSubject());
        return new AuthenticatedUser(
                id,
                extractStrings(jwt.getClaim("roles")),
                extractStrings(jwt.getClaim("permissions"))
        );
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractStrings(Object claim) {
        if (claim == null) {
            return Set.of();
        }
        if (claim instanceof String value) {
            return Set.of(value);
        }
        if (claim instanceof Collection<?> values) {
            Set<String> result = new LinkedHashSet<>();
            for (Object value : values) {
                if (value != null) {
                    result.add(String.valueOf(value));
                }
            }
            return result;
        }
        return Set.of(String.valueOf(claim));
    }
}
