package com.banka1.order.repository;

import com.banka1.order.entity.RecurringOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecurringOrderRepository extends JpaRepository<RecurringOrder, Long> {

    List<RecurringOrder> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("select r from RecurringOrder r where r.active = true and r.nextRun <= :now")
    List<RecurringOrder> findDue(@Param("now") LocalDateTime now);
}
