package com.banka1.order.service.impl;

import com.banka1.order.client.StockClient;
import com.banka1.order.dto.AuthenticatedUser;
import com.banka1.order.dto.CreateBuyOrderRequest;
import com.banka1.order.dto.CreateRecurringOrderRequest;
import com.banka1.order.dto.CreateSellOrderRequest;
import com.banka1.order.dto.OrderResponse;
import com.banka1.order.dto.RecurringOrderDto;
import com.banka1.order.dto.RecurringOrderSkippedNotification;
import com.banka1.order.dto.StockListingDto;
import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringMode;
import com.banka1.order.exception.BusinessConflictException;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.RecurringOrderRepository;
import com.banka1.order.service.OrderCreationService;
import com.banka1.order.service.RecurringOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link RecurringOrderService}.
 *
 * <p>CRUD plus pause/resume for Celina 3.6 standing orders, and the per-order firing
 * logic the {@link com.banka1.order.scheduler.RecurringOrderScheduler} drives. Every
 * mutation resolves the standing order under its owner: a row that belongs to another
 * user is reported as {@link ResourceNotFoundException} (404) so a caller cannot probe
 * whether another user's standing-order id exists.
 *
 * <p>{@link #runDueOrder(Long)} is deliberately <em>not</em> {@code @Transactional}: it
 * delegates to {@link OrderCreationService#createBuyOrder}/{@code confirmOrder}, each of
 * which is already transactional. Letting each run in its own transaction means a failed
 * order ({@link BusinessConflictException}) rolls back only that order — not the standing
 * order's {@code nextRun} advance, and not the other due orders in the batch — and lets
 * {@code confirmOrder}'s {@code afterCommit} async-execution trigger fire normally.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderServiceImpl implements RecurringOrderService {

    /** Recipient id-space discriminator for an actuary/agent standing-order owner. */
    private static final String RECIPIENT_TYPE_EMPLOYEE = "EMPLOYEE";

    /** Recipient id-space discriminator for a client standing-order owner. */
    private static final String RECIPIENT_TYPE_CLIENT = "CLIENT";

    /** Roles given to a synthesized actuary owner so the order flow runs its agent path. */
    private static final Set<String> AGENT_ROLES = Set.of("AGENT");

    /** Roles given to a synthesized client owner so the order flow runs its client path. */
    private static final Set<String> CLIENT_ROLES = Set.of("CLIENT_TRADING");

    /** Trading permission granted to a synthesized client owner. */
    private static final Set<String> CLIENT_PERMISSIONS = Set.of("SECURITIES_TRADE");

    private final RecurringOrderRepository recurringOrderRepository;
    private final OrderCreationService orderCreationService;
    private final StockClient stockClient;
    private final ActuaryInfoRepository actuaryInfoRepository;
    private final OrderNotificationProducer orderNotificationProducer;

    @Override
    @Transactional(readOnly = true)
    public List<RecurringOrderDto> getForUser(Long userId) {
        return recurringOrderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(RecurringOrderDto::from)
                .toList();
    }

    @Override
    @Transactional
    public RecurringOrderDto create(Long userId, CreateRecurringOrderRequest request) {
        RecurringOrder order = new RecurringOrder();
        order.setUserId(userId);
        order.setListingId(request.getListingId());
        order.setDirection(request.getDirection());
        order.setMode(request.getMode());
        order.setValue(request.getValue());
        order.setAccountId(request.getAccountId());
        order.setCadence(request.getCadence());
        order.setNextRun(request.getNextRun());
        order.setActive(true);
        return RecurringOrderDto.from(recurringOrderRepository.save(order));
    }

    @Override
    @Transactional
    public RecurringOrderDto pause(Long userId, Long id) {
        return setActive(userId, id, false);
    }

    @Override
    @Transactional
    public RecurringOrderDto resume(Long userId, Long id) {
        return setActive(userId, id, true);
    }

    @Override
    @Transactional
    public void cancel(Long userId, Long id) {
        recurringOrderRepository.delete(getOwned(userId, id));
    }

    @Override
    public void runDueOrder(Long recurringOrderId) {
        RecurringOrder order = recurringOrderRepository.findById(recurringOrderId).orElse(null);
        if (order == null || !Boolean.TRUE.equals(order.getActive())) {
            // Cancelled or paused after the scheduler picked it up — nothing to fire.
            return;
        }
        try {
            fire(order);
        } catch (BusinessConflictException ex) {
            // Insufficient funds / portfolio — skip this run, notify the owner, but
            // still keep the schedule moving (handled by the finally block).
            log.info("Recurring order {} skipped: {}", order.getId(), ex.getMessage());
            publishSkippedNotification(order, ex.getMessage());
        } catch (RuntimeException ex) {
            // Any other failure (e.g. listing lookup down) must not stall the batch or
            // freeze this standing order — log it and let nextRun advance regardless.
            log.warn("Recurring order {} failed to run; advancing schedule anyway", order.getId(), ex);
        } finally {
            advanceNextRun(order);
        }
    }

    /**
     * Materializes one Market Order for a due standing order. A {@code BY_AMOUNT}
     * instruction whose currency amount buys fewer than one whole share is treated
     * as an insufficient-funds skip.
     *
     * @param order the due standing order
     */
    private void fire(RecurringOrder order) {
        StockListingDto listing = stockClient.getListing(order.getListingId());
        int quantity = resolveQuantity(order, listing);
        if (quantity <= 0) {
            throw new BusinessConflictException(
                    "Insufficient funds: amount does not cover one whole unit");
        }
        AuthenticatedUser user = buildOwner(order.getUserId());
        OrderResponse created = order.getDirection() == OrderDirection.SELL
                ? orderCreationService.createSellOrder(user, buildSellRequest(order, quantity))
                : orderCreationService.createBuyOrder(user, buildBuyRequest(order, quantity));
        orderCreationService.confirmOrder(user, created.getId());
    }

    /**
     * Resolves the whole share quantity for a fired order.
     *
     * <p>For {@code BY_QUANTITY} the {@code value} is the quantity directly. For
     * {@code BY_AMOUNT} (the DCA case) the currency amount is divided by the cost of a
     * single unit — the listing's ask price times the contract size — and rounded down
     * to a whole quantity.
     *
     * @param order   the standing order
     * @param listing the listing's current market data
     * @return the whole quantity to trade; {@code 0} when a {@code BY_AMOUNT} amount
     *         buys less than one unit
     */
    private int resolveQuantity(RecurringOrder order, StockListingDto listing) {
        if (order.getMode() == RecurringMode.BY_QUANTITY) {
            return order.getValue().setScale(0, RoundingMode.DOWN).intValueExact();
        }
        BigDecimal unitCost = unitCost(listing);
        if (unitCost.compareTo(BigDecimal.ZERO) <= 0) {
            // No usable ask price — cannot size the order; treat as a skip.
            return 0;
        }
        return order.getValue().divide(unitCost, 0, RoundingMode.DOWN).intValueExact();
    }

    /**
     * Cost of a single tradable unit — {@code ask price * contract size}. This is the
     * exact divisor used by {@code OrderCreationServiceImpl}'s approximate-price math
     * ({@code pricePerUnit * contractSize * quantity}) for a BUY MARKET order, so
     * dividing a currency amount by it yields a quantity the funds check will accept.
     *
     * @param listing the listing's current market data
     * @return the per-unit cost, or {@code ZERO} when the ask price is unavailable
     */
    private BigDecimal unitCost(StockListingDto listing) {
        BigDecimal ask = listing.getAsk();
        if (ask == null) {
            return BigDecimal.ZERO;
        }
        long contractSize = listing.getContractSize() == null ? 1L : listing.getContractSize();
        return ask.multiply(BigDecimal.valueOf(contractSize));
    }

    /**
     * Builds the buy-order request for a fired standing order. {@code limitValue} and
     * {@code stopValue} are left null so {@code determineOrderType} yields a MARKET order.
     */
    private CreateBuyOrderRequest buildBuyRequest(RecurringOrder order, int quantity) {
        CreateBuyOrderRequest request = new CreateBuyOrderRequest();
        request.setListingId(order.getListingId());
        request.setQuantity(quantity);
        request.setAccountId(order.getAccountId());
        request.setMargin(false);
        request.setAllOrNone(false);
        return request;
    }

    /**
     * Builds the sell-order request for a fired standing order. {@code limitValue} and
     * {@code stopValue} are left null so {@code determineOrderType} yields a MARKET order.
     */
    private CreateSellOrderRequest buildSellRequest(RecurringOrder order, int quantity) {
        CreateSellOrderRequest request = new CreateSellOrderRequest();
        request.setListingId(order.getListingId());
        request.setQuantity(quantity);
        request.setAccountId(order.getAccountId());
        request.setMargin(false);
        request.setAllOrNone(false);
        return request;
    }

    /**
     * Synthesizes the {@link AuthenticatedUser} the order flow expects for the standing
     * order's owner. Actuaries (resolved from {@code actuary_info}) get the agent path —
     * their daily limit is reserved/consumed by the existing approval logic; everyone
     * else gets the client trading path.
     *
     * @param userId the standing order's owner
     * @return an authenticated-user context for the order-creation flow
     */
    private AuthenticatedUser buildOwner(Long userId) {
        if (actuaryInfoRepository.findByEmployeeId(userId).isPresent()) {
            return new AuthenticatedUser(userId, AGENT_ROLES, Set.of());
        }
        return new AuthenticatedUser(userId, CLIENT_ROLES, CLIENT_PERMISSIONS);
    }

    /**
     * Advances a standing order's {@code nextRun} by one cadence step. Reloads the row
     * so a value paused/cancelled mid-run is handled, and persists in its own short
     * transaction (Spring Data wraps {@code save}).
     *
     * @param order the standing order that was just attempted
     */
    private void advanceNextRun(RecurringOrder order) {
        RecurringOrder fresh = recurringOrderRepository.findById(order.getId()).orElse(null);
        if (fresh == null) {
            // Cancelled mid-run — nothing left to advance.
            return;
        }
        fresh.setNextRun(fresh.getCadence().advance(fresh.getNextRun()));
        recurringOrderRepository.save(fresh);
    }

    /**
     * Publishes the skip notification for a standing order whose run could not be
     * placed, the same way a missed loan installment is notified to the client.
     *
     * @param order  the skipped standing order
     * @param reason the human-readable skip reason
     */
    private void publishSkippedNotification(RecurringOrder order, String reason) {
        try {
            RecurringOrderSkippedNotification payload = new RecurringOrderSkippedNotification();
            payload.setRecipientUserId(order.getUserId());
            payload.setRecipientType(resolveRecipientType(order.getUserId()));
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("orderId", String.valueOf(order.getId()));
            variables.put("reason", reason == null ? "Nedovoljno sredstava" : reason);
            variables.put("listingId", String.valueOf(order.getListingId()));
            payload.setTemplateVariables(variables);
            orderNotificationProducer.sendRecurringOrderSkipped(payload);
        } catch (RuntimeException ex) {
            // Best-effort: a notification failure must never break the schedule.
            log.warn("Failed to publish recurring.order_skipped notification for standing order {}",
                    order.getId(), ex);
        }
    }

    /**
     * Resolves the recipient id space for the standing order's owner so the in-app
     * notification lands in the right feed.
     *
     * @param userId the standing order's owner
     * @return {@code EMPLOYEE} for an actuary, {@code CLIENT} otherwise
     */
    private String resolveRecipientType(Long userId) {
        return actuaryInfoRepository.findByEmployeeId(userId).isPresent()
                ? RECIPIENT_TYPE_EMPLOYEE
                : RECIPIENT_TYPE_CLIENT;
    }

    /**
     * Flips the {@code active} flag of an owned standing order.
     *
     * @param userId the owner's identifier
     * @param id     the standing order to update
     * @param active the new active state
     * @return the updated standing order
     */
    private RecurringOrderDto setActive(Long userId, Long id, boolean active) {
        RecurringOrder order = getOwned(userId, id);
        order.setActive(active);
        return RecurringOrderDto.from(recurringOrderRepository.save(order));
    }

    /**
     * Loads a standing order and verifies it belongs to the caller.
     *
     * @param userId the owner's identifier
     * @param id     the standing-order identifier
     * @return the owned standing order
     * @throws ResourceNotFoundException when no such standing order exists for this user
     */
    private RecurringOrder getOwned(Long userId, Long id) {
        return recurringOrderRepository.findById(id)
                .filter(order -> order.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Recurring order not found"));
    }
}
