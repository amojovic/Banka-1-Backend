package com.banka1.tradingservice.audit.repository;

import com.banka1.tradingservice.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * WP-2: Spring Data repozitorijum za {@link AuditLog}.
 *
 * <p>{@link JpaSpecificationExecutor} omogucava dinamicko filtriranje
 * (actionType / actorId / vremenski opseg) iz {@code AuditQueryService}
 * bez rucno pisanih JPQL kombinacija.
 */
@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
