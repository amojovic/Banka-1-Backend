package com.banka1.tradingservice.audit.service;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.domain.AuditLog;
import com.banka1.tradingservice.audit.dto.AuditLogDto;
import com.banka1.tradingservice.audit.repository.AuditLogRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * WP-2: read/filter servis koji podrzava {@code AuditLogController}.
 *
 * <p>Sklapa {@link Specification} iz opcionih filter parametara i vraca
 * stranicu {@link AuditLogDto} (paginaciju + sortiranje obezbedjuje
 * {@link Pageable} iz kontrolera — default je {@code createdAt DESC}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditQueryService {

    private final AuditLogRepository auditLogRepository;

    /**
     * Vraca filtriranu i paginiranu stranicu audit redova.
     *
     * @param actionType opcioni filter po tipu akcije
     * @param actorId    opcioni filter po ID-u aktora
     * @param from       opcioni donji ogranicnik {@code createdAt} (inkluzivno)
     * @param to         opcioni gornji ogranicnik {@code createdAt} (inkluzivno)
     * @param pageable   paginacija + sortiranje (newest-first default postavlja kontroler)
     * @return stranica {@link AuditLogDto}
     */
    public Page<AuditLogDto> search(AuditActionType actionType,
                                    Long actorId,
                                    LocalDateTime from,
                                    LocalDateTime to,
                                    Pageable pageable) {
        Specification<AuditLog> spec = buildSpecification(actionType, actorId, from, to);
        return auditLogRepository.findAll(spec, pageable).map(AuditLogDto::from);
    }

    private Specification<AuditLog> buildSpecification(AuditActionType actionType,
                                                       Long actorId,
                                                       LocalDateTime from,
                                                       LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actionType != null) {
                predicates.add(cb.equal(root.get("actionType"), actionType));
            }
            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
