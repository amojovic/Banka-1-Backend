package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.ForexPair;
import com.banka1.stock_service.domain.FuturesContract;
import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.dto.ListingFilterRequest;
import com.banka1.stock_service.dto.ListingSortField;
import com.banka1.stock_service.dto.ListingSummaryResponse;
import com.banka1.stock_service.repository.ForexPairRepository;
import com.banka1.stock_service.repository.FuturesContractRepository;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import com.banka1.stock_service.service.ListingQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Default implementation of {@link ListingQueryService}.
 *
 * <p>The current listing dataset is small and seed-driven, so the implementation
 * intentionally favors a simpler in-memory composition approach over a larger
 * custom SQL layer. The service:
 *
 * <ul>
 *     <li>loads listings of one requested type</li>
 *     <li>batch-loads the underlying security rows for derived calculations</li>
 *     <li>applies filtering and search in memory</li>
 *     <li>sorts by supported fields, including derived maintenance margin</li>
 *     <li>applies manual pagination to the filtered result</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ListingQueryServiceImpl implements ListingQueryService {

    private final ListingRepository listingRepository;
    private final StockRepository stockRepository;
    private final FuturesContractRepository futuresContractRepository;
    private final ForexPairRepository forexPairRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummaryResponse> getStockListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    ) {
        List<ListingCatalogRow> rows = buildStockRows(listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.STOCK));
        return toPage(rows, filter, page, size, sortField, sortDirection);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummaryResponse> getFuturesListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    ) {
        List<ListingCatalogRow> rows = buildFuturesRows(
                listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.FUTURES)
        );
        return toPage(rows, filter, page, size, sortField, sortDirection);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ListingSummaryResponse> getForexListings(
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    ) {
        List<ListingCatalogRow> rows = buildForexRows(listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.FOREX));
        return toPage(rows, filter, page, size, sortField, sortDirection);
    }

    /**
     * Builds query rows for stock listings together with their derived margins.
     *
     * @param listings stock listings
     * @return derived stock query rows
     */
    private List<ListingCatalogRow> buildStockRows(List<Listing> listings) {
        Map<Long, Stock> stocksById = indexById(stockRepository.findAllById(extractSecurityIds(listings)));
        return listings.stream()
                .map(listing -> {
                    Stock stock = requireUnderlyingSecurity(stocksById, listing, "stock");
                    BigDecimal maintenanceMargin = stock.calculateMaintenanceMargin(listing.getPrice());
                    return new ListingCatalogRow(
                            listing,
                            maintenanceMargin,
                            listing.calculateInitialMarginCost(maintenanceMargin),
                            null
                    );
                })
                .toList();
    }

    /**
     * Builds query rows for futures listings together with settlement date and derived margins.
     *
     * @param listings futures listings
     * @return derived futures query rows
     */
    private List<ListingCatalogRow> buildFuturesRows(List<Listing> listings) {
        Map<Long, FuturesContract> contractsById = indexById(futuresContractRepository.findAllById(extractSecurityIds(listings)));
        return listings.stream()
                .map(listing -> {
                    FuturesContract contract = requireUnderlyingSecurity(contractsById, listing, "futures contract");
                    BigDecimal maintenanceMargin = contract.calculateMaintenanceMargin(listing.getPrice());
                    return new ListingCatalogRow(
                            listing,
                            maintenanceMargin,
                            listing.calculateInitialMarginCost(maintenanceMargin),
                            contract.getSettlementDate()
                    );
                })
                .toList();
    }

    /**
     * Builds query rows for FX listings together with their derived margins.
     *
     * @param listings FX listings
     * @return derived FX query rows
     */
    private List<ListingCatalogRow> buildForexRows(List<Listing> listings) {
        Map<Long, ForexPair> pairsById = indexById(forexPairRepository.findAllById(extractSecurityIds(listings)));
        return listings.stream()
                .map(listing -> {
                    ForexPair pair = requireUnderlyingSecurity(pairsById, listing, "forex pair");
                    BigDecimal maintenanceMargin = pair.calculateMaintenanceMargin();
                    return new ListingCatalogRow(
                            listing,
                            maintenanceMargin,
                            listing.calculateInitialMarginCost(maintenanceMargin),
                            null
                    );
                })
                .toList();
    }

    /**
     * Applies filtering, sorting, and manual pagination to derived listing rows.
     *
     * @param rows derived query rows
     * @param filter filter request
     * @param page zero-based page index
     * @param size page size
     * @param sortField supported sort field
     * @param sortDirection sort direction
     * @return paginated listing response page
     */
    private Page<ListingSummaryResponse> toPage(
            List<ListingCatalogRow> rows,
            ListingFilterRequest filter,
            int page,
            int size,
            ListingSortField sortField,
            Sort.Direction sortDirection
    ) {
        validatePaging(page, size);

        List<ListingCatalogRow> filteredRows = rows.stream()
                .filter(row -> matchesExchange(row.listing(), filter.getExchange()))
                .filter(row -> matchesSearch(row.listing(), filter.getSearch()))
                .filter(row -> matchesRange(row.listing().getPrice(), filter.getMinPrice(), filter.getMaxPrice()))
                .filter(row -> matchesRange(row.listing().getAsk(), filter.getMinAsk(), filter.getMaxAsk()))
                .filter(row -> matchesRange(row.listing().getBid(), filter.getMinBid(), filter.getMaxBid()))
                .filter(row -> matchesRange(row.listing().getVolume(), filter.getMinVolume(), filter.getMaxVolume()))
                .filter(row -> matchesSettlementDate(row, filter.getSettlementDate()))
                .sorted(resolveComparator(sortField, sortDirection))
                .toList();

        int startIndex = Math.min(page * size, filteredRows.size());
        int endIndex = Math.min(startIndex + size, filteredRows.size());

        List<ListingSummaryResponse> content = filteredRows.subList(startIndex, endIndex)
                .stream()
                .map(this::toResponse)
                .toList();

        return new PageImpl<>(content, PageRequest.of(page, size), filteredRows.size());
    }

    /**
     * Validates paging parameters accepted by the public API.
     *
     * @param page zero-based page index
     * @param size page size
     */
    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Page must be zero or greater.");
        }
        if (size <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "Size must be greater than zero.");
        }
    }

    /**
     * Resolves the comparator used for one supported sort field.
     *
     * @param sortField requested sort field
     * @param sortDirection requested sort direction
     * @return comparator with deterministic ticker/id tie-breakers
     */
    private Comparator<ListingCatalogRow> resolveComparator(ListingSortField sortField, Sort.Direction sortDirection) {
        Comparator<ListingCatalogRow> comparator = switch (sortField) {
            case PRICE -> Comparator.comparing(row -> row.listing().getPrice());
            case VOLUME -> Comparator.comparing(row -> row.listing().getVolume());
            case MAINTENANCE_MARGIN -> Comparator.comparing(ListingCatalogRow::maintenanceMargin);
            case TICKER -> Comparator.comparing(row -> row.listing().getTicker(), String.CASE_INSENSITIVE_ORDER);
        };

        if (sortDirection == Sort.Direction.DESC) {
            comparator = comparator.reversed();
        }

        return comparator
                .thenComparing(row -> row.listing().getTicker(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(row -> row.listing().getId());
    }

    /**
     * Checks whether the exchange filter matches one listing exchange by prefix.
     *
     * @param listing listing under evaluation
     * @param exchangePrefix requested prefix
     * @return {@code true} when the filter matches or is absent
     */
    private boolean matchesExchange(Listing listing, String exchangePrefix) {
        if (exchangePrefix == null || exchangePrefix.isBlank()) {
            return true;
        }

        String normalizedPrefix = exchangePrefix.trim().toLowerCase(Locale.ROOT);
        return startsWithIgnoreCase(listing.getStockExchange().getExchangeMICCode(), normalizedPrefix)
                || startsWithIgnoreCase(listing.getStockExchange().getExchangeAcronym(), normalizedPrefix)
                || startsWithIgnoreCase(listing.getStockExchange().getExchangeName(), normalizedPrefix);
    }

    /**
     * Checks whether the free-text search matches listing ticker or name.
     *
     * @param listing listing under evaluation
     * @param searchTerm requested search term
     * @return {@code true} when the filter matches or is absent
     */
    private boolean matchesSearch(Listing listing, String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return true;
        }

        String normalizedSearch = searchTerm.trim().toLowerCase(Locale.ROOT);
        return containsIgnoreCase(listing.getTicker(), normalizedSearch)
                || containsIgnoreCase(listing.getName(), normalizedSearch);
    }

    /**
     * Checks whether one comparable value is inside the inclusive filter range.
     *
     * @param value stored row value
     * @param minimum optional inclusive lower bound
     * @param maximum optional inclusive upper bound
     * @param <T> comparable value type
     * @return {@code true} when the range matches
     */
    private <T extends Comparable<T>> boolean matchesRange(T value, T minimum, T maximum) {
        if (minimum != null && value.compareTo(minimum) < 0) {
            return false;
        }
        if (maximum != null && value.compareTo(maximum) > 0) {
            return false;
        }
        return true;
    }

    /**
     * Applies the futures settlement-date filter when present.
     *
     * @param row derived listing row
     * @param settlementDate requested settlement date
     * @return {@code true} when the filter matches or is absent
     */
    private boolean matchesSettlementDate(ListingCatalogRow row, LocalDate settlementDate) {
        if (settlementDate == null) {
            return true;
        }
        return Objects.equals(row.settlementDate(), settlementDate);
    }

    /**
     * Converts one derived row into the public response contract.
     *
     * @param row derived row
     * @return public response row
     */
    private ListingSummaryResponse toResponse(ListingCatalogRow row) {
        return new ListingSummaryResponse(
                row.listing().getId(),
                row.listing().getListingType(),
                row.listing().getTicker(),
                row.listing().getName(),
                row.listing().getStockExchange().getExchangeMICCode(),
                row.listing().getPrice(),
                row.listing().getChange(),
                row.listing().getVolume(),
                row.initialMarginCost(),
                row.settlementDate()
        );
    }

    /**
     * Extracts security ids from listings for batch loading of underlying entities.
     *
     * @param listings listings of one category
     * @return underlying security ids
     */
    private Collection<Long> extractSecurityIds(List<Listing> listings) {
        return listings.stream()
                .map(Listing::getSecurityId)
                .toList();
    }

    /**
     * Indexes JPA entities by id.
     *
     * @param entities entities to index
     * @param <T> entity type exposing {@code getId()}
     * @return id-indexed entity map
     */
    private <T> Map<Long, T> indexById(Collection<T> entities) {
        return entities.stream().collect(Collectors.toMap(this::extractId, Function.identity()));
    }

    /**
     * Resolves the id of one known stock-domain entity type.
     *
     * @param entity entity instance
     * @return extracted id
     */
    private Long extractId(Object entity) {
        if (entity instanceof Stock stock) {
            return stock.getId();
        }
        if (entity instanceof FuturesContract futuresContract) {
            return futuresContract.getId();
        }
        if (entity instanceof ForexPair forexPair) {
            return forexPair.getId();
        }
        throw new IllegalArgumentException("Unsupported entity type: " + entity.getClass().getName());
    }

    /**
     * Resolves the underlying security row for one listing.
     *
     * @param entitiesById batch-loaded entities
     * @param listing listing whose security row is needed
     * @param entityLabel label used in the error message
     * @param <T> underlying security type
     * @return matching underlying security
     */
    private <T> T requireUnderlyingSecurity(Map<Long, T> entitiesById, Listing listing, String entityLabel) {
        T entity = entitiesById.get(listing.getSecurityId());
        if (entity == null) {
            throw new IllegalStateException(
                    "Underlying %s with id %s was not found for listing %s."
                            .formatted(entityLabel, listing.getSecurityId(), listing.getId())
            );
        }
        return entity;
    }

    /**
     * Checks whether one value starts with the provided normalized prefix.
     *
     * @param value raw value
     * @param normalizedPrefix already-lowercased prefix
     * @return {@code true} when the value matches
     */
    private boolean startsWithIgnoreCase(String value, String normalizedPrefix) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix);
    }

    /**
     * Checks whether one value contains the provided normalized fragment.
     *
     * @param value raw value
     * @param normalizedFragment already-lowercased search fragment
     * @return {@code true} when the value matches
     */
    private boolean containsIgnoreCase(String value, String normalizedFragment) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedFragment);
    }

    /**
     * Internal immutable row used while filtering and sorting listing catalogs.
     *
     * @param listing listing entity
     * @param maintenanceMargin derived maintenance margin used for sorting
     * @param initialMarginCost derived initial margin cost returned to callers
     * @param settlementDate futures settlement date when applicable
     */
    private record ListingCatalogRow(
            Listing listing,
            BigDecimal maintenanceMargin,
            BigDecimal initialMarginCost,
            LocalDate settlementDate
    ) {
    }
}
