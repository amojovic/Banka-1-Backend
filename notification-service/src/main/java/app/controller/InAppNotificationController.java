package app.controller;

import app.dto.InAppNotificationDto;
import app.entities.RecipientType;
import app.service.InAppNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for the per-user in-app notification feed.
 *
 * <p>All endpoints live under {@code /notifications} (the FCM token endpoints
 * sit under the disjoint {@code /notifications/fcm} prefix) and are JWT-secured
 * — every read and mutation is scoped to the caller resolved from the token.
 *
 * <p>The caller identity is the JWT {@code id} claim; the id space (client vs
 * employee) is derived from the {@code roles} claim because the numeric id is
 * not globally unique across the two identity stores.
 */
@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
@Tag(name = "In-App Notifications", description = "Per-user notification feed for the web client")
public class InAppNotificationController {

    /** Default page size when the caller does not supply one. */
    private static final int DEFAULT_PAGE_SIZE = 20;
    /** Upper bound on page size to keep responses bounded. */
    private static final int MAX_PAGE_SIZE = 100;
    /** Role-claim prefix that identifies a bank client (vs an employee). */
    private static final String CLIENT_ROLE_PREFIX = "CLIENT_";

    /** Application service backing the feed. */
    private final InAppNotificationService service;

    /**
     * Returns one page of the caller's notifications, newest first.
     *
     * @param jwt  authenticated caller
     * @param page zero-based page index (negative values are clamped to 0)
     * @param size page size (clamped to [1, {@value #MAX_PAGE_SIZE}])
     * @return page of notification DTOs scoped to the caller
     */
    @GetMapping
    @Operation(summary = "List the caller's notifications, newest first")
    public ResponseEntity<Page<InAppNotificationDto>> list(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ResponseEntity.ok(service.getNotifications(
                userId(jwt), recipientType(jwt), safePage, safeSize));
    }

    /**
     * Returns the count of the caller's unread notifications.
     *
     * @param jwt authenticated caller
     * @return JSON object {@code { "count": <number> }}
     */
    @GetMapping("/unread-count")
    @Operation(summary = "Count the caller's unread notifications")
    public ResponseEntity<Map<String, Long>> unreadCount(@AuthenticationPrincipal Jwt jwt) {
        long count = service.unreadCount(userId(jwt), recipientType(jwt));
        return ResponseEntity.ok(Map.of("count", count));
    }

    /**
     * Marks a single notification read. The update only succeeds when the
     * notification belongs to the caller.
     *
     * @param jwt authenticated caller
     * @param id  notification primary key
     * @return HTTP 204 on success, HTTP 404 when the notification does not
     *         exist or does not belong to the caller
     */
    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark a single notification read")
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt,
                                         @PathVariable Long id) {
        boolean updated = service.markRead(id, userId(jwt), recipientType(jwt));
        return updated ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Marks every unread notification owned by the caller as read.
     *
     * @param jwt authenticated caller
     * @return HTTP 204 (idempotent — succeeds even when nothing was unread)
     */
    @PatchMapping("/read-all")
    @Operation(summary = "Mark all of the caller's notifications read")
    public ResponseEntity<Void> markAllRead(@AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(userId(jwt), recipientType(jwt));
        return ResponseEntity.noContent().build();
    }

    /**
     * Extracts the caller id from the JWT {@code id} claim, falling back to the
     * {@code sub} claim — mirrors the convention used by the other services.
     *
     * @param jwt authenticated caller token
     * @return numeric caller id
     */
    private Long userId(Jwt jwt) {
        Object idClaim = jwt.getClaim("id");
        if (idClaim instanceof Number number) {
            return number.longValue();
        }
        if (idClaim != null) {
            return Long.valueOf(idClaim.toString());
        }
        return Long.valueOf(jwt.getSubject());
    }

    /**
     * Derives the caller id space from the {@code roles} claim. Client roles are
     * prefixed {@code CLIENT_} ({@code CLIENT_BASIC}, {@code CLIENT_TRADING});
     * every other role belongs to an employee.
     *
     * @param jwt authenticated caller token
     * @return {@link RecipientType#CLIENT} or {@link RecipientType#EMPLOYEE}
     */
    private RecipientType recipientType(Jwt jwt) {
        Object rolesClaim = jwt.getClaim("roles");
        for (String role : asRoleList(rolesClaim)) {
            if (role != null && role.toUpperCase().startsWith(CLIENT_ROLE_PREFIX)) {
                return RecipientType.CLIENT;
            }
        }
        return RecipientType.EMPLOYEE;
    }

    /**
     * Normalizes a {@code roles} claim that may arrive as a single string or as
     * a list of strings into a flat list.
     *
     * @param rolesClaim raw claim value
     * @return list of role strings, never {@code null}
     */
    @SuppressWarnings("unchecked")
    private List<String> asRoleList(Object rolesClaim) {
        if (rolesClaim instanceof String single) {
            return List.of(single);
        }
        if (rolesClaim instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
