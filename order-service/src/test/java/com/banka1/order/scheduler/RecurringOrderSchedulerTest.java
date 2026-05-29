package com.banka1.order.scheduler;

import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import com.banka1.order.repository.RecurringOrderRepository;
import com.banka1.order.service.RecurringOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecurringOrderSchedulerTest {

    @Mock
    private RecurringOrderRepository recurringOrderRepository;

    @Mock
    private RecurringOrderService recurringOrderService;

    private RecurringOrderScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RecurringOrderScheduler(recurringOrderRepository, recurringOrderService);
    }

    @Test
    void runDueRecurringOrders_dispatchesEachDueOrderById() {
        when(recurringOrderRepository.findDue(any(LocalDateTime.class)))
                .thenReturn(List.of(entity(1L), entity(2L), entity(3L)));

        scheduler.runDueRecurringOrders();

        verify(recurringOrderService).runDueOrder(1L);
        verify(recurringOrderService).runDueOrder(2L);
        verify(recurringOrderService).runDueOrder(3L);
    }

    @Test
    void runDueRecurringOrders_noDueOrders_dispatchesNothing() {
        when(recurringOrderRepository.findDue(any(LocalDateTime.class))).thenReturn(List.of());

        scheduler.runDueRecurringOrders();

        verifyNoInteractions(recurringOrderService);
    }

    @Test
    void runDueRecurringOrders_hasExpectedCron() throws Exception {
        Method method = RecurringOrderScheduler.class.getDeclaredMethod("runDueRecurringOrders");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 */15 * * * *");
    }

    private RecurringOrder entity(Long id) {
        RecurringOrder order = new RecurringOrder();
        order.setId(id);
        order.setUserId(42L);
        order.setListingId(99L);
        order.setDirection(OrderDirection.BUY);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.00"));
        order.setAccountId(5L);
        order.setCadence(RecurringCadence.MONTHLY);
        order.setNextRun(LocalDateTime.now().minusHours(1));
        order.setActive(true);
        return order;
    }
}
