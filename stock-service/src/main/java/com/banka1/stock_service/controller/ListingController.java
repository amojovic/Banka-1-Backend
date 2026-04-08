package com.banka1.stock_service.controller;

import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingRefreshResponse;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.service.ListingMarketDataRefreshService;
import com.banka1.stock_service.service.ListingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Listing API for listing-catalog queries and manual market-data refresh operations.
 */
@RestController
@RequiredArgsConstructor
public class ListingController {

    private final ListingMarketDataRefreshService listingMarketDataRefreshService;
    private final ListingQueryService listingQueryService;

    /**
     * Returns paginated stock listings available to clients and actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated stock listings
     */
    @GetMapping("/api/listings/stocks")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getStockListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getStockListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated futures listings available to clients and actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated futures listings
     */
    @GetMapping("/api/listings/futures")
    @PreAuthorize("hasAnyRole('CLIENT_BASIC', 'BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getFuturesListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getFuturesListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Returns paginated FX listings available only to actuary-side users.
     *
     * @param filter shared listing filters
     * @param page zero-based page index
     * @param size page size
     * @param sortBy supported sort field
     * @param sortDirection sort direction
     * @return paginated FX listings
     */
    @GetMapping("/api/listings/forex")
    @PreAuthorize("hasAnyRole('BASIC', 'AGENT', 'SUPERVISOR', 'ADMIN', 'SERVICE')")
    public ResponseEntity<Page<ListingSummaryResponse>> getForexListings(
            @ModelAttribute ListingFilterRequest filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ticker") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection
    ) {
        Page<ListingSummaryResponse> response = listingQueryService.getForexListings(
                filter,
                page,
                size,
                ListingSortField.fromParameter(sortBy),
                resolveSortDirection(sortDirection)
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Manually refreshes one listing snapshot by id.
     *
     * @param id listing identifier
     * @return refresh summary
     */
    @PostMapping("/api/listings/{id}/refresh")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERVISOR')")
    public ResponseEntity<ListingRefreshResponse> refreshListing(@PathVariable Long id) {
        ListingRefreshResponse response = listingMarketDataRefreshService.refreshListing(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Parses the sort-direction query parameter used by catalog endpoints.
     *
     * @param sortDirection raw query-parameter value
     * @return parsed Spring sort direction
     */
    private Sort.Direction resolveSortDirection(String sortDirection) {
        return Sort.Direction.fromOptionalString(sortDirection)
                .orElseThrow(() -> new ResponseStatusException(
                        BAD_REQUEST,
                        "Unsupported sortDirection value '%s'. Supported values are asc and desc."
                                .formatted(sortDirection)
                ));
    }
}
