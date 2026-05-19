package com.banka1.order.service.impl;

import com.banka1.order.audit.AuditEventDto;
import com.banka1.order.audit.AuditPublisher;
import com.banka1.order.client.EmployeeClient;
import com.banka1.order.dto.ActuaryAgentDto;
import com.banka1.order.dto.ActuaryProfitDto;
import com.banka1.order.dto.BankProfitSummaryDto;
import com.banka1.order.dto.EmployeeDto;
import com.banka1.order.dto.EmployeePageResponse;
import com.banka1.order.dto.SetLimitRequestDto;
import com.banka1.order.dto.SetNeedApprovalRequestDto;
import com.banka1.order.entity.ActuaryInfo;
import com.banka1.order.exception.ResourceNotFoundException;
import com.banka1.order.repository.ActuaryInfoRepository;
import com.banka1.order.repository.TransactionRepository;
import com.banka1.order.security.Role;
import com.banka1.order.service.ActuaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of {@link ActuaryService}.
 *
 * Manages actuary (agent and supervisor) account information including daily trading limits,
 * approval requirements, and limit consumption tracking.
 *
 * Combines employee data from employee-service with local {@link ActuaryInfo} records
 * to provide a complete view of actuary permissions and restrictions.
 *
 * Key Responsibilities:
 * <ul>
 *   <li>Retrieve agents with pagination and filtering</li>
 *   <li>Merge employee data with actuary trading limits</li>
 *   <li>Update daily trading limits for agents</li>
 *   <li>Sync actuary data from employee-service</li>
 *   <li>Calculate remaining daily trading limit</li>
 * </ul>
 *
 * Service Integrations:
 * <ul>
 *   <li>employee-service: Fetch employee data with role-based filtering</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ActuaryServiceImpl implements ActuaryService {

    private static final int EMPLOYEE_PAGE_SIZE = 100;

    private final ActuaryInfoRepository actuaryInfoRepository;
    private final EmployeeClient employeeClient;
    /** PR_14 C14.9: za sumiranje komisija po aktuaru. */
    private final TransactionRepository transactionRepository;
    /** WP-12: producer za centralizovani audit log (Celina 3.5). */
    private final AuditPublisher auditPublisher;

    /**
     * {@inheritDoc}
     * Fetches employees from employee-service across all pages, filters to AGENT role,
     * and merges with local actuary limit data.
     */
    @Override
    public Page<ActuaryAgentDto> getAgents(String email, String ime, String prezime, String pozicija, Pageable pageable) {
        boolean singleTermSearch = ime != null && ime.equals(prezime);

        Map<Long, ActuaryAgentDto> agentMap = new LinkedHashMap<>();

        if (singleTermSearch) {
            fetchAgents(email, ime, null, pozicija, agentMap);
            fetchAgents(email, null, prezime, pozicija, agentMap);
        } else {
            fetchAgents(email, ime, prezime, pozicija, agentMap);
        }

        List<ActuaryAgentDto> agents = new java.util.ArrayList<>(agentMap.values());

        if (pageable.isUnpaged()) {
            return new PageImpl<>(agents, pageable, agents.size());
        }
        int start = (int) pageable.getOffset();
        int end = Math.min(start + pageable.getPageSize(), agents.size());
        List<ActuaryAgentDto> slice = start >= agents.size() ? List.of() : agents.subList(start, end);
        return new PageImpl<>(slice, pageable, agents.size());
    }

    private void fetchAgents(String email, String ime, String prezime, String pozicija, Map<Long, ActuaryAgentDto> agentMap) {
        int pageIndex = 0;
        while (true) {
            EmployeePageResponse page = employeeClient.searchEmployees(email, ime, prezime, pozicija, pageIndex, EMPLOYEE_PAGE_SIZE);
            if (page == null || page.getContent() == null || page.getContent().isEmpty()) {
                break;
            }

            page.getContent().stream()
                    .filter(emp -> Role.AGENT.matches(emp.getRole()))
                    .forEach(emp -> agentMap.computeIfAbsent(emp.getId(), id -> {
                        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(id)
                                .orElseGet(() -> createDefaultActuaryInfo(id));
                        return toDto(emp, info);
                    }));

            pageIndex++;
            if (pageIndex >= page.getTotalPages()) {
                break;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void setLimit(Long actorId, Long employeeId, SetLimitRequestDto request) {
        EmployeeDto employee = fetchEmployeeOrNotFound(employeeId);

        if (Role.ADMIN.matches(employee.getRole())) {
            throw new IllegalArgumentException("Cannot change the limit of an admin.");
        }
        if (!Role.AGENT.matches(employee.getRole())) {
            throw new IllegalArgumentException("Limit can only be set for employees with the AGENT role.");
        }

        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseGet(() -> createDefaultActuaryInfo(employeeId));

        if (request.getLimit().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Limit must be greater than zero.");
        }
        if (request.getLimit().compareTo(info.getUsedLimit()) < 0) {
            throw new IllegalArgumentException("Limit cannot be lower than the current used limit of " + info.getUsedLimit() + ".");
        }

        BigDecimal oldLimit = info.getLimit();
        info.setLimit(request.getLimit());
        actuaryInfoRepository.save(info);
        publishAuditEvent(actorId, "AGENT_LIMIT_CHANGED", employeeId,
                "Limit promenjen: " + oldLimit + " -> " + request.getLimit());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void resetLimit(Long actorId, Long employeeId) {
        EmployeeDto employee = fetchEmployeeOrNotFound(employeeId);

        if (Role.ADMIN.matches(employee.getRole())) {
            throw new IllegalArgumentException("Cannot reset the limit of an admin.");
        }
        if (!Role.AGENT.matches(employee.getRole())) {
            throw new IllegalArgumentException("Limit can only be reset for employees with the AGENT role.");
        }

        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseGet(() -> createDefaultActuaryInfo(employeeId));

        BigDecimal oldUsedLimit = info.getUsedLimit();
        info.setUsedLimit(BigDecimal.ZERO);
        info.setReservedLimit(BigDecimal.ZERO);
        actuaryInfoRepository.save(info);
        publishAuditEvent(actorId, "AGENT_USED_LIMIT_RESET", employeeId,
                "Iskorisceni limit resetovan: " + oldUsedLimit + " -> 0");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void setNeedApproval(Long actorId, Long employeeId, SetNeedApprovalRequestDto request) {
        EmployeeDto employee = fetchEmployeeOrNotFound(employeeId);

        if (Role.ADMIN.matches(employee.getRole())) {
            throw new IllegalArgumentException("Cannot change the need-approval flag of an admin.");
        }
        if (!Role.AGENT.matches(employee.getRole())) {
            throw new IllegalArgumentException("The need-approval flag can only be set for employees with the AGENT role.");
        }

        ActuaryInfo info = actuaryInfoRepository.findByEmployeeId(employeeId)
                .orElseGet(() -> createDefaultActuaryInfo(employeeId));

        Boolean oldNeedApproval = info.getNeedApproval();
        boolean newNeedApproval = Boolean.TRUE.equals(request.getNeedApproval());
        info.setNeedApproval(newNeedApproval);
        actuaryInfoRepository.save(info);
        publishAuditEvent(actorId, "AGENT_NEED_APPROVAL_CHANGED", employeeId,
                "needApproval promenjen: " + Boolean.TRUE.equals(oldNeedApproval) + " -> " + newNeedApproval);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Transactional
    public void resetAllLimits() {
        log.info("Resetting limit consumption state for all actuary records.");
        List<ActuaryInfo> all = actuaryInfoRepository.findAll();
        for (ActuaryInfo info : all) {
            info.setUsedLimit(BigDecimal.ZERO);
            info.setReservedLimit(BigDecimal.ZERO);
        }
        actuaryInfoRepository.saveAll(all);
    }

    /**
     * Creates and persists a new {@link ActuaryInfo} with default values for the given employee.
     * Used when an agent appears in employee-service but has no local actuary record yet.
     *
     * @param employeeId the employee's identifier
     * @return the newly saved actuary record
     */
    private EmployeeDto fetchEmployeeOrNotFound(Long employeeId) {
        try {
            return employeeClient.getEmployee(employeeId);
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ResourceNotFoundException("Employee with ID " + employeeId + " not found.");
        }
    }

    private ActuaryInfo createDefaultActuaryInfo(Long employeeId) {
        ActuaryInfo info = new ActuaryInfo();
        info.setEmployeeId(employeeId);
        info.setUsedLimit(BigDecimal.ZERO);
        info.setReservedLimit(BigDecimal.ZERO);
        info.setNeedApproval(false);
        return actuaryInfoRepository.save(info);
    }

    /**
     * WP-12: publikuje audit dogadjaj o izmeni agenta na centralizovani audit
     * log. Salje se strogo posle commita transakcije, tako da rollback-ovana
     * izmena ne ostavlja audit red. Kada nema transakcije (npr. unit test bez
     * sinhronizacije), poruka se salje odmah.
     *
     * @param actorId    id supervizora koji je doneo izmenu
     * @param actionType ime {@code AuditActionType} konstante
     * @param employeeId id agenta nad kojim je izmena izvrsena
     * @param details    citljiv opis stare i nove vrednosti
     */
    private void publishAuditEvent(Long actorId, String actionType, Long employeeId, String details) {
        String actorName = resolveActorName(actorId);
        AuditEventDto event = new AuditEventDto(
                actorId,
                actorName,
                actionType,
                "AGENT",
                String.valueOf(employeeId),
                details,
                System.currentTimeMillis());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    auditPublisher.publish(event);
                }
            });
        } else {
            auditPublisher.publish(event);
        }
    }

    /**
     * Razresava citljivo ime aktora preko employee-service-a. Best-effort —
     * ako lookup pukne ili je {@code actorId} null, vraca string id / "SYSTEM".
     *
     * @param actorId id supervizora
     * @return citljivo ime, string id, ili "SYSTEM"
     */
    private String resolveActorName(Long actorId) {
        if (actorId == null) {
            return "SYSTEM";
        }
        try {
            EmployeeDto employee = employeeClient.getEmployee(actorId);
            if (employee != null) {
                String firstName = employee.getIme() == null ? "" : employee.getIme().trim();
                String lastName = employee.getPrezime() == null ? "" : employee.getPrezime().trim();
                String fullName = (firstName + " " + lastName).trim();
                return fullName.isEmpty() ? String.valueOf(actorId) : fullName;
            }
        } catch (RuntimeException ex) {
            log.debug("audit: ne mogu da razresim ime aktora {}: {}", actorId, ex.toString());
        }
        return String.valueOf(actorId);
    }

    /**
     * Maps an employee DTO and its actuary info to the combined response DTO.
     *
     * @param emp  employee data from employee-service
     * @param info local actuary limit record
     * @return combined agent DTO
     */
    private ActuaryAgentDto toDto(EmployeeDto emp, ActuaryInfo info) {
        ActuaryAgentDto dto = new ActuaryAgentDto();
        dto.setEmployeeId(emp.getId());
        dto.setIme(emp.getIme());
        dto.setPrezime(emp.getPrezime());
        dto.setEmail(emp.getEmail());
        dto.setPozicija(emp.getPozicija());
        dto.setLimit(info.getLimit());
        dto.setUsedLimit(info.getUsedLimit());
        dto.setNeedApproval(info.getNeedApproval());
        return dto;
    }

    @Override
    public List<ActuaryProfitDto> profitByActuary(LocalDateTime from, LocalDateTime to) {
        // PR_021: Postgres ne moze da odredi tip parametra za "? is null" pattern u JPQL-u
        // (PSQLException: could not determine data type of parameter $1). Substituiraj null
        // sa sentinel-vrednostima koje obuhvataju ceo period.
        LocalDateTime effectiveFrom = (from != null) ? from : LocalDateTime.of(1900, 1, 1, 0, 0);
        LocalDateTime effectiveTo = (to != null) ? to : LocalDateTime.of(9999, 12, 31, 23, 59);
        return transactionRepository.sumCommissionByActuary(effectiveFrom, effectiveTo).stream()
                .map(row -> {
                    ActuaryProfitDto.ActuaryProfitDtoBuilder b = ActuaryProfitDto.builder()
                            .userId(row.getUserId())
                            .totalCommission(row.getTotalCommission() != null ? row.getTotalCommission() : BigDecimal.ZERO)
                            .transactionCount(row.getTransactionCount() != null ? row.getTransactionCount() : 0L);
                    // PR_15 C15.7: enrich sa imenom employee-a; ako lookup pukne (npr. employee-service down)
                    // toleriramo i ostavljamo polja null.
                    try {
                        EmployeeDto emp = employeeClient.getEmployee(row.getUserId());
                        if (emp != null) {
                            b.ime(emp.getIme()).prezime(emp.getPrezime()).pozicija(emp.getPozicija());
                        }
                    } catch (Exception ex) {
                        log.debug("Profit enrichment skip za userId={}: {}", row.getUserId(), ex.toString());
                    }
                    return b.build();
                })
                .toList();
    }

    @Override
    public BankProfitSummaryDto bankProfitSummary(LocalDateTime from, LocalDateTime to) {
        List<ActuaryProfitDto> perActuary = profitByActuary(from, to);
        BigDecimal totalCommission = perActuary.stream()
                .map(ActuaryProfitDto::getTotalCommission)
                .filter(c -> c != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalTransactions = perActuary.stream()
                .map(ActuaryProfitDto::getTransactionCount)
                .filter(c -> c != null)
                .mapToLong(Long::longValue)
                .sum();
        return BankProfitSummaryDto.builder()
                .totalCommission(totalCommission)
                .transactionCount(totalTransactions)
                .distinctActuaries((long) perActuary.size())
                .from(from)
                .to(to)
                .build();
    }
}
