package com.banka1.order.service;

import com.banka1.order.dto.AuthenticatedUser;
import com.banka1.order.dto.CreateBuyOrderRequest;
import com.banka1.order.dto.CreateSellOrderRequest;
import com.banka1.order.dto.OrderOverviewResponse;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderOverviewStatusFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for creating buy and sell orders.
 */
public interface OrderCreationService {

    /**
     * Creates a buy order with validation and approval logic.
     *
     * @param userId the ID of the user placing the order
     * @param request the buy order request
     * @return the created order response
     */
    OrderResponse createBuyOrder(AuthenticatedUser user, CreateBuyOrderRequest request);

    /**
     * Creates a sell order with validation and approval logic.
     *
     * @param userId the ID of the user placing the order
     * @param request the sell order request
     * @return the created order response
     */
    OrderResponse createSellOrder(AuthenticatedUser user, CreateSellOrderRequest request);

    /**
     * Returns the supervisor portal overview of orders with optional status filtering.
     *
     * @param statusFilter the requested status filter
     * @return overview rows for the portal
     */
    Page<OrderOverviewResponse> getOrders(OrderOverviewStatusFilter statusFilter, Pageable pageable);

    /**
     * Returns orders owned by the authenticated client.
     *
     * @param user the authenticated client
     * @return orders created by the client
     */
    List<OrderResponse> getMyOrders(AuthenticatedUser user);

    /**
     * Returns a filtered, paginated page of orders owned by the authenticated client.
     * Backs the mobile My Orders screen; the existing {@link #getMyOrders(AuthenticatedUser)}
     * stays unchanged for the web frontend.
     *
     * @param user the authenticated client
     * @param statusFilter optional status filter (ALL or null for any status)
     * @param listingType optional security-type filter (null for any)
     * @param dateFrom optional inclusive lower bound on the order creation date (null for unbounded)
     * @param dateTo optional inclusive upper bound on the order creation date (null for unbounded)
     * @param pageable paging request
     * @return enriched order responses owned by the client, newest first
     */
    Page<OrderResponse> getMyOrdersPaged(AuthenticatedUser user, OrderOverviewStatusFilter statusFilter,
                                         ListingType listingType, LocalDate dateFrom, LocalDate dateTo, Pageable pageable);

    /**
     * Confirms a draft order and finalizes validation, approval state, and fee transfer.
     *
     * @param user the authenticated owner of the order
     * @param orderId the order to confirm
     * @return the updated order response
     */
    OrderResponse confirmOrder(AuthenticatedUser user, Long orderId);

    /**
     * Cancels a not-yet-completed order.
     *
     * @param user the authenticated owner of the order
     * @param orderId the order to cancel
     * @return the updated order response
     */
    OrderResponse cancelOrder(AuthenticatedUser user, Long orderId);

    /**
     * Cancels only the remaining unexecuted portion of an order for supervisor portal actions.
     *
     * @param orderId the order to cancel
     * @return the updated order response
     */
    OrderResponse cancelOrder(Long orderId);

    /**
     * Cancels the remaining unfilled portion, or part of it, for supervisor actions.
     *
     * @param orderId the order to cancel
     * @param quantityToCancel null to cancel all remaining quantity, otherwise the quantity to cancel from the remainder
     * @return the updated order response
     */
    OrderResponse cancelOrder(Long orderId, Integer quantityToCancel);

    /**
     * Approves a pending actuary order.
     *
     * @param supervisorId the approving supervisor
     * @param orderId the order to approve
     * @return the updated order response
     */
    OrderResponse approveOrder(Long supervisorId, Long orderId);

    /**
     * Declines a pending actuary order.
     *
     * @param supervisorId the declining supervisor
     * @param orderId the order to decline
     * @return the updated order response
     */
    OrderResponse declineOrder(Long supervisorId, Long orderId);

    /**
     * Automatically declines pending orders whose settlement date has passed.
     */
    void autoDeclineExpiredPendingOrders();
}
