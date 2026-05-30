package com.banka1.tradingservice.audit.controller;

import com.banka1.tradingservice.audit.domain.AuditActionType;
import com.banka1.tradingservice.audit.dto.AuditLogDto;
import com.banka1.tradingservice.audit.service.AuditQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * WP-2: read-only API za centralizovani audit log.
 *
 * <p>{@code GET /audit} vraca {@link Page} {@link AuditLogDto}-a, najnoviji prvi.
 * Svi filter parametri su opcioni. Pristup je ogranicen na ADMIN i SUPERVISOR
 * uloge ({@code @PreAuthorize}).
 *
 * <p>{@code from}/{@code to} prihvataju ISO datum ({@code 2026-05-18}) ili
 * datum-vreme ({@code 2026-05-18T14:30:00}). Bare datum se za {@code from}
 * tretira kao pocetak dana, za {@code to} kao kraj dana (inkluzivni opseg na
 * {@code createdAt}).
 */
@RestController
@RequestMapping("/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 200;

    private final AuditQueryService auditQueryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ResponseEntity<Page<AuditLogDto>> getAuditLog(
            @RequestParam(required = false) String actionType,
            @RequestParam(required = false) Long actorId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        AuditActionType parsedActionType = parseActionType(actionType);
        LocalDateTime parsedFrom = parseRangeBound(from, "from", true);
        LocalDateTime parsedTo = parseRangeBound(to, "to", false);

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                clampSize(size),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<AuditLogDto> result =
                auditQueryService.search(parsedActionType, actorId, parsedFrom, parsedTo, pageable);
        return ResponseEntity.ok(result);
    }

    private int clampSize(int size) {
        if (size < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private AuditActionType parseActionType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return AuditActionType.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Nepoznat actionType: '" + raw + "'");
        }
    }

    /**
     * Parsira datum/datum-vreme granicnik. Bare datum se siri na pocetak dana
     * ({@code from}) odnosno kraj dana ({@code to}).
     */
    private LocalDateTime parseRangeBound(String raw, String paramName, boolean startOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // padback na bare datum
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return startOfDay ? date.atStartOfDay() : date.atTime(23, 59, 59, 999_999_999);
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Neispravan datum za '" + paramName + "': '" + raw
                            + "' (ocekivan ISO datum ili datum-vreme)");
        }
    }
}
