package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.AddWatchlistItemRequest;
import com.banka1.stock_service.dto.CreateWatchlistRequest;
import com.banka1.stock_service.dto.WatchlistDto;
import com.banka1.stock_service.dto.WatchlistItemDto;
import com.banka1.stock_service.service.WatchlistService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;

/**
 * Watchlist API (Celina 3.4).
 *
 * <p>A watchlist lets a user follow a personalized set of securities without
 * buying them. Every endpoint is JWT-secured and scoped to the caller: the
 * owner of a watchlist is the user identified by the JWT {@code id} claim, and
 * a watchlist that the caller does not own is reported as
 * {@code 404 NOT_FOUND}.
 *
 * <p>The feature is available to clients and to actuaries (agents and
 * supervisors), so every endpoint is gated with the matching trading roles.
 */
@RestController
@RequestMapping("/watchlists")
@RequiredArgsConstructor
public class WatchlistController {

    /**
     * Roles allowed to manage watchlists — bank clients plus actuaries
     * (agents and supervisors); {@code ADMIN} is included for operational
     * parity with the other Celina 3 endpoints.
     */
    private static final String WATCHLIST_ROLES =
            "hasAnyRole('CLIENT_BASIC', 'CLIENT_TRADING', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN')";

    private final WatchlistService watchlistService;

    /**
     * Returns the watchlists owned by the caller.
     *
     * @param authentication authenticated caller
     * @return the caller's watchlists with their item counts
     */
    @Operation(summary = "List the caller's watchlists")
    @GetMapping
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<List<WatchlistDto>> getMyWatchlists(Authentication authentication) {
        return ResponseEntity.ok(watchlistService.getWatchlistsForUser(resolveUserId(authentication)));
    }

    /**
     * Creates a new watchlist for the caller.
     *
     * @param request watchlist definition
     * @param authentication authenticated caller
     * @return the created watchlist
     */
    @Operation(summary = "Create a watchlist")
    @PostMapping
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<WatchlistDto> createWatchlist(
            @Valid @RequestBody CreateWatchlistRequest request,
            Authentication authentication
    ) {
        WatchlistDto created = watchlistService.createWatchlist(resolveUserId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Deletes one watchlist owned by the caller together with its items.
     *
     * @param id watchlist identifier
     * @param authentication authenticated caller
     * @return empty {@code 204 NO_CONTENT} response
     */
    @Operation(summary = "Delete a watchlist")
    @DeleteMapping("/{id}")
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<Void> deleteWatchlist(
            @PathVariable Long id,
            Authentication authentication
    ) {
        watchlistService.deleteWatchlist(resolveUserId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the followed securities of one watchlist owned by the caller,
     * each enriched with the listing's current market data.
     *
     * @param id watchlist identifier
     * @param listingType optional security-type filter
     * @param authentication authenticated caller
     * @return the watchlist items, optionally filtered by security type
     */
    @Operation(summary = "List the items of a watchlist")
    @GetMapping("/{id}/items")
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<List<WatchlistItemDto>> getWatchlistItems(
            @PathVariable Long id,
            @RequestParam(name = "listingType", required = false) String listingType,
            Authentication authentication
    ) {
        return ResponseEntity.ok(watchlistService.getItems(
                resolveUserId(authentication), id, parseListingType(listingType)));
    }

    /**
     * Adds one followed security to a watchlist owned by the caller.
     *
     * @param id watchlist identifier
     * @param request followed-security definition
     * @param authentication authenticated caller
     * @return the created watchlist item enriched with the listing's market data
     */
    @Operation(summary = "Add a security to a watchlist")
    @PostMapping("/{id}/items")
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<WatchlistItemDto> addWatchlistItem(
            @PathVariable Long id,
            @Valid @RequestBody AddWatchlistItemRequest request,
            Authentication authentication
    ) {
        WatchlistItemDto created = watchlistService.addItem(resolveUserId(authentication), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Removes one followed security from a watchlist owned by the caller.
     *
     * @param id watchlist identifier
     * @param itemId watchlist item identifier
     * @param authentication authenticated caller
     * @return empty {@code 204 NO_CONTENT} response
     */
    @Operation(summary = "Remove a security from a watchlist")
    @DeleteMapping("/{id}/items/{itemId}")
    @PreAuthorize(WATCHLIST_ROLES)
    public ResponseEntity<Void> removeWatchlistItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            Authentication authentication
    ) {
        watchlistService.removeItem(resolveUserId(authentication), id, itemId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Parses the optional {@code listingType} query parameter into a {@link ListingType}.
     *
     * @param listingType raw query value, may be {@code null} or blank
     * @return parsed listing type, or {@code null} when the parameter is absent
     */
    private ListingType parseListingType(String listingType) {
        if (listingType == null || listingType.isBlank()) {
            return null;
        }
        try {
            return ListingType.valueOf(listingType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException illegalArgumentException) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported listingType '%s'. Supported values are STOCK, FUTURES, FOREX and OPTION."
                            .formatted(listingType));
        }
    }

    /**
     * Extracts the caller identifier from the JWT {@code id} claim.
     *
     * <p>The claim is accepted both as a JSON number and as a numeric string so
     * the endpoint tolerates the slight differences in how the issuing services
     * encode the {@code id} claim.
     *
     * @param authentication authenticated caller
     * @return caller identifier
     */
    private Long resolveUserId(Authentication authentication) {
        Jwt jwt = requireJwt(authentication);
        Object idClaim = jwt.getClaim("id");
        if (idClaim instanceof Number number) {
            return number.longValue();
        }
        if (idClaim instanceof String stringId && !stringId.isBlank()) {
            try {
                return Long.parseLong(stringId.trim());
            } catch (NumberFormatException numberFormatException) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Token id claim is not a valid identifier.");
            }
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token is missing the id claim.");
    }

    /**
     * Returns the JWT principal of an authenticated caller.
     *
     * @param authentication authenticated caller
     * @return the JWT principal
     */
    private Jwt requireJwt(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing authentication token.");
    }
}
