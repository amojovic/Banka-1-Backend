package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for persisting and querying {@link PriceAlert} entities.
 */
public interface PriceAlertRepository extends JpaRepository<PriceAlert, Long> {

    /**
     * Returns all alerts owned by one user, newest first.
     *
     * @param userId owner identifier from the JWT {@code id} claim
     * @return alerts belonging to the user ordered by creation time descending
     */
    List<PriceAlert> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * Returns all alerts that are currently active and should be evaluated by
     * the scheduler after a market-data refresh.
     *
     * @return active alerts
     */
    List<PriceAlert> findByActiveTrue();
}
