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
 * Implementation of {@link RecurringOrderService} — Celina 3.6 standing orders.
 *
 * <p>{@link #runDueOrder(Long)} is not {@code @Transactional}: it delegates to
 * {@link OrderCreationService#createBuyOrder}/{@code confirmOrder}, each already
 * transactional, so a failed order rolls back only that order's transaction, not
 * the schedule advance and not the other due orders in the batch.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderServiceImpl implements RecurringOrderService {

    private static final Set<String> AGENT_ROLES = Set.of("AGENT");
    private static final Set<String> CLIENT_ROLES = Set.of("CLIENT_TRADING");
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
            return;
        }
        try {
            fire(order);
        } catch (BusinessConflictException ex) {
            log.info("Recurring order {} skipped: {}", order.getId(), ex.getMessage());
            publishSkippedNotification(order, ex.getMessage());
        } catch (RuntimeException ex) {
            log.warn("Recurring order {} failed to run; advancing schedule anyway", order.getId(), ex);
        } finally {
            advanceNextRun(order);
        }
    }

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

    private int resolveQuantity(RecurringOrder order, StockListingDto listing) {
        if (order.getMode() == RecurringMode.BY_QUANTITY) {
            return order.getValue().setScale(0, RoundingMode.DOWN).intValueExact();
        }
        BigDecimal unitCost = unitCost(listing);
        if (unitCost.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }
        return order.getValue().divide(unitCost, 0, RoundingMode.DOWN).intValueExact();
    }

    private BigDecimal unitCost(StockListingDto listing) {
        BigDecimal ask = listing.getAsk();
        if (ask == null) {
            return BigDecimal.ZERO;
        }
        long contractSize = listing.getContractSize() == null ? 1L : listing.getContractSize();
        return ask.multiply(BigDecimal.valueOf(contractSize));
    }

    private CreateBuyOrderRequest buildBuyRequest(RecurringOrder order, int quantity) {
        CreateBuyOrderRequest request = new CreateBuyOrderRequest();
        request.setListingId(order.getListingId());
        request.setQuantity(quantity);
        request.setAccountId(order.getAccountId());
        request.setMargin(false);
        request.setAllOrNone(false);
        return request;
    }

    private CreateSellOrderRequest buildSellRequest(RecurringOrder order, int quantity) {
        CreateSellOrderRequest request = new CreateSellOrderRequest();
        request.setListingId(order.getListingId());
        request.setQuantity(quantity);
        request.setAccountId(order.getAccountId());
        request.setMargin(false);
        request.setAllOrNone(false);
        return request;
    }

    private AuthenticatedUser buildOwner(Long userId) {
        if (actuaryInfoRepository.findByEmployeeId(userId).isPresent()) {
            return new AuthenticatedUser(userId, AGENT_ROLES, Set.of());
        }
        return new AuthenticatedUser(userId, CLIENT_ROLES, CLIENT_PERMISSIONS);
    }

    private void advanceNextRun(RecurringOrder order) {
        RecurringOrder fresh = recurringOrderRepository.findById(order.getId()).orElse(null);
        if (fresh == null) {
            return;
        }
        fresh.setNextRun(fresh.getCadence().advance(fresh.getNextRun()));
        recurringOrderRepository.save(fresh);
    }

    private void publishSkippedNotification(RecurringOrder order, String reason) {
        try {
            RecurringOrderSkippedNotification payload = new RecurringOrderSkippedNotification();
            // Set clientId only for client owners — FCM fires for them; null for actuaries.
            boolean isActuary = actuaryInfoRepository.findByEmployeeId(order.getUserId()).isPresent();
            payload.setClientId(isActuary ? null : order.getUserId());
            Map<String, String> variables = new LinkedHashMap<>();
            variables.put("orderId", String.valueOf(order.getId()));
            variables.put("reason", reason == null ? "Nedovoljno sredstava" : reason);
            variables.put("listingId", String.valueOf(order.getListingId()));
            payload.setTemplateVariables(variables);
            orderNotificationProducer.sendRecurringOrderSkipped(payload);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish order.recurring_skipped for standing order {}",
                    order.getId(), ex);
        }
    }

    private RecurringOrderDto setActive(Long userId, Long id, boolean active) {
        RecurringOrder order = getOwned(userId, id);
        order.setActive(active);
        return RecurringOrderDto.from(recurringOrderRepository.save(order));
    }

    private RecurringOrder getOwned(Long userId, Long id) {
        return recurringOrderRepository.findById(id)
                .filter(order -> order.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Recurring order not found"));
    }
}
