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
import com.banka1.order.entity.ActuaryInfo;
import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import com.banka1.order.exception.BusinessConflictException;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.rabbitmq.OrderNotificationProducer;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.RecurringOrderRepository;
import com.banka1.order.service.OrderCreationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * WP-13: unit tests for {@link RecurringOrderServiceImpl} — CRUD plus pause/resume
 * (with ownership enforcement) and the {@code runDueOrder} firing logic.
 */
@ExtendWith(MockitoExtension.class)
class RecurringOrderServiceImplTest {

    @Mock
    private RecurringOrderRepository recurringOrderRepository;
    @Mock
    private OrderCreationService orderCreationService;
    @Mock
    private StockClient stockClient;
    @Mock
    private ActuaryInfoRepository actuaryInfoRepository;
    @Mock
    private OrderNotificationProducer orderNotificationProducer;

    private RecurringOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RecurringOrderServiceImpl(recurringOrderRepository, orderCreationService,
                stockClient, actuaryInfoRepository, orderNotificationProducer);
    }

    // ---------------------------------------------------------------- CRUD

    @Test
    void getForUser_mapsRepositoryRowsToDtos() {
        when(recurringOrderRepository.findByUserIdOrderByCreatedAtDesc(42L))
                .thenReturn(List.of(entity(1L, 42L, true), entity(2L, 42L, false)));

        List<RecurringOrderDto> result = service.getForUser(42L);

        assertThat(result).extracting(RecurringOrderDto::getId).containsExactly(1L, 2L);
        assertThat(result.getFirst().getUserId()).isEqualTo(42L);
    }

    @Test
    void create_persistsStandingOrderOwnedByCallerAndActive() {
        when(recurringOrderRepository.save(any(RecurringOrder.class)))
                .thenAnswer(invocation -> {
                    RecurringOrder saved = invocation.getArgument(0);
                    saved.setId(7L);
                    return saved;
                });

        RecurringOrderDto created = service.create(42L, request());

        ArgumentCaptor<RecurringOrder> captor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepository).save(captor.capture());
        RecurringOrder persisted = captor.getValue();
        assertThat(persisted.getUserId()).isEqualTo(42L);
        assertThat(persisted.getListingId()).isEqualTo(99L);
        assertThat(persisted.getDirection()).isEqualTo(OrderDirection.BUY);
        assertThat(persisted.getMode()).isEqualTo(RecurringMode.BY_AMOUNT);
        assertThat(persisted.getValue()).isEqualByComparingTo("10000.00");
        assertThat(persisted.getAccountId()).isEqualTo(5L);
        assertThat(persisted.getCadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(persisted.getActive()).isTrue();
        assertThat(created.getId()).isEqualTo(7L);
    }

    @Test
    void pause_setsActiveFalseForOwnedOrder() {
        RecurringOrder owned = entity(3L, 42L, true);
        when(recurringOrderRepository.findById(3L)).thenReturn(Optional.of(owned));
        when(recurringOrderRepository.save(any(RecurringOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecurringOrderDto result = service.pause(42L, 3L);

        assertThat(result.getActive()).isFalse();
        assertThat(owned.getActive()).isFalse();
    }

    @Test
    void resume_setsActiveTrueForOwnedOrder() {
        RecurringOrder owned = entity(3L, 42L, false);
        when(recurringOrderRepository.findById(3L)).thenReturn(Optional.of(owned));
        when(recurringOrderRepository.save(any(RecurringOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RecurringOrderDto result = service.resume(42L, 3L);

        assertThat(result.getActive()).isTrue();
        assertThat(owned.getActive()).isTrue();
    }

    @Test
    void cancel_deletesOwnedOrder() {
        RecurringOrder owned = entity(3L, 42L, true);
        when(recurringOrderRepository.findById(3L)).thenReturn(Optional.of(owned));

        service.cancel(42L, 3L);

        verify(recurringOrderRepository).delete(owned);
    }

    @Test
    void pause_notOwned_throwsNotFoundAndDoesNotSave() {
        when(recurringOrderRepository.findById(3L)).thenReturn(Optional.of(entity(3L, 999L, true)));

        assertThatThrownBy(() -> service.pause(42L, 3L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Recurring order not found");
        verify(recurringOrderRepository, never()).save(any());
    }

    @Test
    void cancel_notOwned_throwsNotFoundAndDoesNotDelete() {
        when(recurringOrderRepository.findById(3L)).thenReturn(Optional.of(entity(3L, 999L, true)));

        assertThatThrownBy(() -> service.cancel(42L, 3L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(recurringOrderRepository, never()).delete(any());
    }

    @Test
    void pause_missingOrder_throwsNotFound() {
        when(recurringOrderRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.pause(42L, 404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ----------------------------------------------------------- runDueOrder

    @Test
    void runDueOrder_dueByQuantityOrder_createsAndConfirmsMarketOrder() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        OrderResponse created = orderResponse(1234L);
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(created);

        service.runDueOrder(5L);

        ArgumentCaptor<CreateBuyOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateBuyOrderRequest.class);
        verify(orderCreationService).createBuyOrder(any(AuthenticatedUser.class), reqCaptor.capture());
        CreateBuyOrderRequest request = reqCaptor.getValue();
        assertThat(request.getListingId()).isEqualTo(99L);
        assertThat(request.getQuantity()).isEqualTo(3);
        assertThat(request.getAccountId()).isEqualTo(5L);
        // MARKET order — no limit/stop.
        assertThat(request.getLimitValue()).isNull();
        assertThat(request.getStopValue()).isNull();
        verify(orderCreationService).confirmOrder(any(AuthenticatedUser.class), eq(1234L));
    }

    @Test
    void runDueOrder_byAmountOrder_convertsAmountToWholeQuantityViaAskPrice() {
        // 10000 / (ask 150 * contractSize 1) = 66.66... -> floor 66
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.00"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<CreateBuyOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateBuyOrderRequest.class);
        verify(orderCreationService).createBuyOrder(any(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getQuantity()).isEqualTo(66);
    }

    @Test
    void runDueOrder_byAmountOrder_honoursContractSizeInQuantityConversion() {
        // 10000 / (ask 100 * contractSize 25) = 4.0 -> 4
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.00"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("100.00"), 25));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<CreateBuyOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateBuyOrderRequest.class);
        verify(orderCreationService).createBuyOrder(any(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getQuantity()).isEqualTo(4);
    }

    @Test
    void runDueOrder_sellDirection_createsSellOrder() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setDirection(OrderDirection.SELL);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("2"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createSellOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<CreateSellOrderRequest> reqCaptor = ArgumentCaptor.forClass(CreateSellOrderRequest.class);
        verify(orderCreationService).createSellOrder(any(), reqCaptor.capture());
        assertThat(reqCaptor.getValue().getQuantity()).isEqualTo(2);
        verify(orderCreationService).confirmOrder(any(), eq(1234L));
        verify(orderCreationService, never()).createBuyOrder(any(), any());
    }

    @Test
    void runDueOrder_advancesNextRunByCadenceOnSuccess() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        order.setCadence(RecurringCadence.MONTHLY);
        order.setNextRun(LocalDateTime.of(2026, 6, 1, 0, 0));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<RecurringOrder> savedCaptor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getNextRun()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    }

    @Test
    void runDueOrder_advancesNextRunByOneDayForDailyCadence() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        order.setCadence(RecurringCadence.DAILY);
        order.setNextRun(LocalDateTime.of(2026, 6, 1, 9, 0));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<RecurringOrder> savedCaptor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getNextRun()).isEqualTo(LocalDateTime.of(2026, 6, 2, 9, 0));
    }

    @Test
    void runDueOrder_advancesNextRunBySevenDaysForWeeklyCadence() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        order.setCadence(RecurringCadence.WEEKLY);
        order.setNextRun(LocalDateTime.of(2026, 6, 1, 9, 0));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<RecurringOrder> savedCaptor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getNextRun()).isEqualTo(LocalDateTime.of(2026, 6, 8, 9, 0));
    }

    @Test
    void runDueOrder_insufficientFunds_skipsNotifiesAndStillAdvancesNextRun() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        order.setCadence(RecurringCadence.MONTHLY);
        order.setNextRun(LocalDateTime.of(2026, 6, 1, 0, 0));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any()))
                .thenThrow(new BusinessConflictException("Insufficient funds"));

        service.runDueOrder(5L);

        // skip notification published
        ArgumentCaptor<RecurringOrderSkippedNotification> notifCaptor =
                ArgumentCaptor.forClass(RecurringOrderSkippedNotification.class);
        verify(orderNotificationProducer).sendRecurringOrderSkipped(notifCaptor.capture());
        RecurringOrderSkippedNotification notification = notifCaptor.getValue();
        assertThat(notification.getRecipientUserId()).isEqualTo(42L);
        assertThat(notification.getRecipientType()).isEqualTo("CLIENT");
        assertThat(notification.getTemplateVariables()).containsEntry("reason", "Insufficient funds");
        assertThat(notification.getTemplateVariables()).containsEntry("orderId", "5");
        // order never confirmed
        verify(orderCreationService, never()).confirmOrder(any(), anyLong());
        // schedule still advanced
        ArgumentCaptor<RecurringOrder> savedCaptor = ArgumentCaptor.forClass(RecurringOrder.class);
        verify(recurringOrderRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getNextRun()).isEqualTo(LocalDateTime.of(2026, 7, 1, 0, 0));
    }

    @Test
    void runDueOrder_insufficientFundsAtConfirm_skipsNotifiesAndAdvances() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));
        when(orderCreationService.confirmOrder(any(), eq(1234L)))
                .thenThrow(new BusinessConflictException("Insufficient funds"));

        service.runDueOrder(5L);

        verify(orderNotificationProducer).sendRecurringOrderSkipped(any());
        verify(recurringOrderRepository).save(any(RecurringOrder.class));
    }

    @Test
    void runDueOrder_byAmountBuysZeroShares_skipsWithoutCreatingOrder() {
        // value 100, ask 150 -> 100 / 150 = 0.66 -> floor 0 shares
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("100.00"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        lenient().when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());

        service.runDueOrder(5L);

        verify(orderCreationService, never()).createBuyOrder(any(), any());
        verify(orderNotificationProducer).sendRecurringOrderSkipped(any());
        // schedule still advanced
        verify(recurringOrderRepository).save(any(RecurringOrder.class));
    }

    @Test
    void runDueOrder_pausedOrder_doesNothing() {
        RecurringOrder order = entity(5L, 42L, false);
        when(recurringOrderRepository.findById(5L)).thenReturn(Optional.of(order));

        service.runDueOrder(5L);

        verifyNoInteractions(stockClient, orderCreationService, orderNotificationProducer);
        verify(recurringOrderRepository, never()).save(any());
    }

    @Test
    void runDueOrder_missingOrder_doesNothing() {
        when(recurringOrderRepository.findById(404L)).thenReturn(Optional.empty());

        service.runDueOrder(404L);

        verifyNoInteractions(stockClient, orderCreationService, orderNotificationProducer);
        verify(recurringOrderRepository, never()).save(any());
    }

    @Test
    void runDueOrder_actuaryOwner_firesOrderUnderAgentRole() {
        RecurringOrder order = entity(5L, 7L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(7L)).thenReturn(Optional.of(new ActuaryInfo()));
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<AuthenticatedUser> userCaptor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(orderCreationService).createBuyOrder(userCaptor.capture(), any());
        AuthenticatedUser user = userCaptor.getValue();
        assertThat(user.userId()).isEqualTo(7L);
        assertThat(user.isAgent()).isTrue();
        assertThat(user.isClient()).isFalse();
    }

    @Test
    void runDueOrder_clientOwner_firesOrderUnderClientTradingRole() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        givenStandingOrder(order);
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        ArgumentCaptor<AuthenticatedUser> userCaptor = ArgumentCaptor.forClass(AuthenticatedUser.class);
        verify(orderCreationService).createBuyOrder(userCaptor.capture(), any());
        AuthenticatedUser user = userCaptor.getValue();
        assertThat(user.isClient()).isTrue();
        assertThat(user.hasTradingPermission()).isTrue();
    }

    @Test
    void runDueOrder_byAmountWithNoAskPrice_skipsAndAdvances() {
        // listing has a null ask -> the order cannot be sized -> treated as a skip
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.00"));
        givenStandingOrder(order);
        StockListingDto noAsk = listing(new BigDecimal("150.00"), 1);
        noAsk.setAsk(null);
        when(stockClient.getListing(99L)).thenReturn(noAsk);
        lenient().when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());

        service.runDueOrder(5L);

        verify(orderCreationService, never()).createBuyOrder(any(), any());
        verify(orderNotificationProducer).sendRecurringOrderSkipped(any());
        verify(recurringOrderRepository).save(any(RecurringOrder.class));
    }

    @Test
    void runDueOrder_orderCancelledMidRun_advanceIsSkipped() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        // pickup sees the order; the advanceNextRun reload sees it gone (cancelled mid-run)
        when(recurringOrderRepository.findById(5L))
                .thenReturn(Optional.of(order))
                .thenReturn(Optional.empty());
        when(stockClient.getListing(99L)).thenReturn(listing(new BigDecimal("150.00"), 1));
        when(actuaryInfoRepository.findByEmployeeId(42L)).thenReturn(Optional.empty());
        when(orderCreationService.createBuyOrder(any(), any())).thenReturn(orderResponse(1234L));

        service.runDueOrder(5L);

        // the order still fired, but there is no row left to advance
        verify(orderCreationService).confirmOrder(any(), eq(1234L));
        verify(recurringOrderRepository, never()).save(any(RecurringOrder.class));
    }

    @Test
    void runDueOrder_unexpectedFailure_isSwallowedAndScheduleStillAdvances() {
        RecurringOrder order = entity(5L, 42L, true);
        order.setMode(RecurringMode.BY_QUANTITY);
        order.setValue(new BigDecimal("3"));
        givenStandingOrder(order);
        // listing lookup fails outright — not a BusinessConflictException
        when(stockClient.getListing(99L)).thenThrow(new RuntimeException("stock-service unavailable"));

        service.runDueOrder(5L);

        verify(orderCreationService, never()).createBuyOrder(any(), any());
        // schedule still advanced despite the failure
        verify(recurringOrderRepository).save(any(RecurringOrder.class));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Stubs both repository reads {@code runDueOrder} performs — the initial pickup and
     * the reload inside {@code advanceNextRun} — to return the same standing order, and
     * makes {@code save} echo its argument.
     */
    private void givenStandingOrder(RecurringOrder order) {
        when(recurringOrderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        lenient().when(recurringOrderRepository.save(any(RecurringOrder.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private StockListingDto listing(BigDecimal ask, int contractSize) {
        StockListingDto listing = new StockListingDto();
        listing.setId(99L);
        listing.setAsk(ask);
        listing.setBid(ask);
        listing.setPrice(ask);
        listing.setContractSize(contractSize);
        listing.setCurrency("USD");
        return listing;
    }

    private OrderResponse orderResponse(Long id) {
        OrderResponse response = new OrderResponse();
        response.setId(id);
        return response;
    }

    private CreateRecurringOrderRequest request() {
        CreateRecurringOrderRequest request = new CreateRecurringOrderRequest();
        request.setListingId(99L);
        request.setDirection(OrderDirection.BUY);
        request.setMode(RecurringMode.BY_AMOUNT);
        request.setValue(new BigDecimal("10000.00"));
        request.setAccountId(5L);
        request.setCadence(RecurringCadence.MONTHLY);
        request.setNextRun(LocalDateTime.now().plusDays(7));
        return request;
    }

    private RecurringOrder entity(Long id, Long userId, boolean active) {
        RecurringOrder order = new RecurringOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setListingId(99L);
        order.setDirection(OrderDirection.BUY);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.00"));
        order.setAccountId(5L);
        order.setCadence(RecurringCadence.MONTHLY);
        order.setNextRun(LocalDateTime.now().plusDays(7));
        order.setActive(active);
        order.setCreatedAt(LocalDateTime.now());
        return order;
    }
}
