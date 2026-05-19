package com.banka1.order.service;

import com.banka1.order.dto.CreateRecurringOrderRequest;
import com.banka1.order.dto.RecurringOrderDto;

import java.util.List;

/**
 * Service for managing standing (recurring) orders — Celina 3.6 dollar-cost-averaging.
 *
 * <p>Every mutation is scoped to the calling user; a standing order that does not belong
 * to the caller is treated as not found.
 */
public interface RecurringOrderService {

    /**
     * Returns every standing order owned by the given user, newest first.
     *
     * @param userId the owner's identifier (from the caller's JWT)
     * @return the user's standing orders
     */
    List<RecurringOrderDto> getForUser(Long userId);

    /**
     * Creates a new standing order owned by the given user.
     *
     * @param userId  the owner's identifier (from the caller's JWT)
     * @param request the standing-order parameters
     * @return the created standing order
     */
    RecurringOrderDto create(Long userId, CreateRecurringOrderRequest request);

    /**
     * Pauses a standing order so the scheduler stops firing it ({@code active=false}).
     *
     * @param userId the owner's identifier (from the caller's JWT)
     * @param id     the standing order to pause
     * @return the updated standing order
     */
    RecurringOrderDto pause(Long userId, Long id);

    /**
     * Resumes a paused standing order ({@code active=true}).
     *
     * @param userId the owner's identifier (from the caller's JWT)
     * @param id     the standing order to resume
     * @return the updated standing order
     */
    RecurringOrderDto resume(Long userId, Long id);

    /**
     * Cancels (deletes) a standing order.
     *
     * @param userId the owner's identifier (from the caller's JWT)
     * @param id     the standing order to cancel
     */
    void cancel(Long userId, Long id);

    /**
     * Fires one due standing order: materializes a Market Order for its owner, then
     * advances {@code nextRun} by one cadence step. Invoked by
     * {@link com.banka1.order.scheduler.RecurringOrderScheduler} once per due order
     * so each runs in its own transaction.
     *
     * <p>If the owner has insufficient funds the order is skipped — the owner gets a
     * notification and {@code nextRun} is still advanced so the schedule continues.
     * The same skip path applies when a {@code BY_AMOUNT} instruction's currency
     * amount buys fewer than one whole share at the current ask price. Any other
     * failure is swallowed so one bad standing order cannot stall the batch; the
     * schedule still advances.
     *
     * @param recurringOrderId the standing order to fire
     */
    void runDueOrder(Long recurringOrderId);
}
