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
 * Fires all active standing orders whose {@code nextRun} has passed.
 * Runs every 15 minutes, consistent with the other order-service schedulers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringOrderScheduler {

    private final RecurringOrderRepository recurringOrderRepository;
    private final RecurringOrderService recurringOrderService;

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
