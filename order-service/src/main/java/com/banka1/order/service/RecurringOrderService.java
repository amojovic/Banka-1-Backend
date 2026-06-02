package com.banka1.order.service;

import com.banka1.order.dto.CreateRecurringOrderRequest;
import com.banka1.order.dto.RecurringOrderDto;

import java.util.List;

public interface RecurringOrderService {

    List<RecurringOrderDto> getForUser(Long userId);

    RecurringOrderDto create(Long userId, CreateRecurringOrderRequest request);

    RecurringOrderDto pause(Long userId, Long id);

    RecurringOrderDto resume(Long userId, Long id);

    void cancel(Long userId, Long id);

    /**
     * Fires one due standing order: materializes a Market Order for its owner, advances
     * {@code nextRun} by one cadence step. Invoked per due order by the scheduler.
     *
     * <p>Insufficient funds → skip, notify owner, still advance schedule.
     * Any other failure is swallowed so one bad order cannot stall the batch.
     */
    void runDueOrder(Long recurringOrderId);
}
