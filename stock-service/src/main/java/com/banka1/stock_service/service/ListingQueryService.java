package com.banka1.stock_service.service;

import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingDetailsPeriod;
import com.banka1.stock_service.dto.ListingDetailsResponse;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;

/**
 * Query service for the listing catalog endpoints.
 *
 * <p>The service keeps one shared implementation for stock, futures, and FX
 * listing catalogs so filtering, derived margin calculations, sorting, and
 * manual pagination behave consistently across all three endpoints.
 */
public interface ListingQueryService {

    /**
     * Loads one detailed listing view with type-specific fields and historical price rows.
     *
     * @param listingId listing identifier
     * @param period requested history window
     * @return detailed listing response
     */
    ListingDetailsResponse getListingDetails(Long listingId, ListingDetailsPeriod period);

    /**
     * Loads paginated stock listings.
     *
     * @param filter filter request
     * @param page zero-based page index
     * @param size page size
     * @param sortField supported sort field
     * @param sortDirection sort direction
     * @return paginated stock listings
     */
    Page<ListingSummaryResponse> getStockListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    );

    /**
     * Loads paginated futures listings.
     *
     * @param filter filter request
     * @param page zero-based page index
     * @param size page size
     * @param sortField supported sort field
     * @param sortDirection sort direction
     * @return paginated futures listings
     */
    Page<ListingSummaryResponse> getFuturesListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    );

    /**
     * Loads paginated FX listings.
     *
     * @param filter filter request
     * @param page zero-based page index
     * @param size page size
     * @param sortField supported sort field
     * @param sortDirection sort direction
     * @return paginated FX listings
     */
    Page<ListingSummaryResponse> getForexListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    );
}
