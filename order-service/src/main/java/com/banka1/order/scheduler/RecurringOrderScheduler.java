package com.banka1.order.scheduler;

import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.repository.RecurringOrderRepository;
import com.banka1.order.service.RecurringOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Walks the active standing (recurring) orders and fires the ones that are due —
 * Celina 3.6 dollar-cost-averaging.
 *
 * <p>Every 15 minutes the scheduler loads the standing orders whose {@code nextRun}
 * has passed and asks {@link RecurringOrderService#runDueOrder(Long)} to materialize
 * a Market Order for each. Each due order is dispatched by id in its own transaction,
 * so one order that cannot be placed (insufficient funds) is skipped without rolling
 * back the others, and {@code confirmOrder}'s async-execution trigger fires normally.
 *
 * <p>Uses a plain {@code @Scheduled} without {@code @SchedulerLock}, consistent with
 * the other order-service schedulers ({@code ActuaryScheduler},
 * {@code ExpiredPendingOrderScheduler}, {@code TaxScheduler}). ShedLock is intentionally
 * not introduced — its {@code shedlock} table is not provisioned in the {@code trading}
 * database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderScheduler {

    private final RecurringOrderRepository recurringOrderRepository;
    private final RecurringOrderService recurringOrderService;

    /**
     * Fires all standing orders whose next run is due. Runs every 15 minutes.
     */
    @Scheduled(cron = "0 */15 * * * *")
    public void runDueRecurringOrders() {
        List<RecurringOrder> due = recurringOrderRepository.findDue(LocalDateTime.now());
        if (due.isEmpty()) {
            return;
        }
        log.info("Firing {} due recurring order(s)", due.size());
        for (RecurringOrder order : due) {
            recurringOrderService.runDueOrder(order.getId());
        }
    }
}
