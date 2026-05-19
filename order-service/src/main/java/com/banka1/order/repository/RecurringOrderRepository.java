package com.banka1.order.repository;

import com.banka1.order.entity.RecurringOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for {@link RecurringOrder} standing orders (Celina 3.6).
 */
@Repository
public interface RecurringOrderRepository extends JpaRepository<RecurringOrder, Long> {

    /**
     * Returns all standing orders owned by a user, newest first.
     *
     * @param userId the owner's identifier
     * @return the user's standing orders
     */
    List<RecurringOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Returns the standing orders the scheduler must fire now — those that are active
     * and whose {@code nextRun} is at or before the given moment.
     *
     * @param now the current wall-clock timestamp
     * @return the due, active standing orders
     */
    @Query("select r from RecurringOrder r where r.active = true and r.nextRun <= :now")
    List<RecurringOrder> findDue(@Param("now") LocalDateTime now);
}
