package com.banka1.stock_service.service.implementation;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Watchlist;
import com.banka1.stock_service.domain.WatchlistItem;
import com.banka1.stock_service.dto.AddWatchlistItemRequest;
import com.banka1.stock_service.dto.CreateWatchlistRequest;
import com.banka1.stock_service.dto.WatchlistDto;
import com.banka1.stock_service.dto.WatchlistItemDto;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.WatchlistItemRepository;
import com.banka1.stock_service.repository.WatchlistRepository;
import com.banka1.stock_service.service.WatchlistService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Default CRUD implementation for user-defined watchlists (Celina 3.4).
 *
 * <p>Ownership is enforced on every operation that targets a specific
 * watchlist: a watchlist can only be read or mutated when its {@code userId}
 * matches the caller. A mismatch is reported as {@code 404 NOT_FOUND} so the
 * existence of other users' watchlists is not leaked.
 *
 * <p>Watchlist items are stored with only a {@code listingId} reference; when
 * they are served, the implementation enriches each item with the current
 * market data of the {@link Listing} so the watchlist always reflects the
 * latest snapshot.
 */
@Service
public class WatchlistServiceImpl implements WatchlistService {

    private final WatchlistRepository watchlistRepository;
    private final WatchlistItemRepository watchlistItemRepository;
    private final ListingRepository listingRepository;
    private final Clock clock;

    /**
     * Creates the production service using the system UTC clock.
     *
     * @param watchlistRepository repository for watchlists
     * @param watchlistItemRepository repository for watchlist items
     * @param listingRepository repository for listings, used to validate
     *                          {@code listingId} and to enrich items with market data
     */
    @Autowired
    public WatchlistServiceImpl(
            WatchlistRepository watchlistRepository,
            WatchlistItemRepository watchlistItemRepository,
            ListingRepository listingRepository
    ) {
        this(watchlistRepository, watchlistItemRepository, listingRepository, Clock.systemUTC());
    }

    /**
     * Creates the service with an explicit clock for deterministic tests.
     *
     * @param watchlistRepository repository for watchlists
     * @param watchlistItemRepository repository for watchlist items
     * @param listingRepository repository for listings, used to validate
     *                          {@code listingId} and to enrich items with market data
     * @param clock time source used for the {@code createdAt} and {@code addedAt} timestamps
     */
    WatchlistServiceImpl(
            WatchlistRepository watchlistRepository,
            WatchlistItemRepository watchlistItemRepository,
            ListingRepository listingRepository,
            Clock clock
    ) {
        this.watchlistRepository = watchlistRepository;
        this.watchlistItemRepository = watchlistItemRepository;
        this.listingRepository = listingRepository;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistDto> getWatchlistsForUser(Long userId) {
        return watchlistRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(watchlist -> WatchlistDto.from(
                        watchlist,
                        watchlistItemRepository.countByWatchlistId(watchlist.getId())))
                .toList();
    }

    @Override
    @Transactional
    public WatchlistDto createWatchlist(Long userId, CreateWatchlistRequest request) {
        String name = normalizeName(request.name());

        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setName(name);
        watchlist.setCreatedAt(LocalDateTime.now(clock));

        return WatchlistDto.from(watchlistRepository.save(watchlist), 0L);
    }

    @Override
    @Transactional
    public void deleteWatchlist(Long userId, Long watchlistId) {
        Watchlist watchlist = requireOwnedWatchlist(userId, watchlistId);
        watchlistItemRepository.deleteByWatchlistId(watchlist.getId());
        watchlistRepository.delete(watchlist);
    }

    @Override
    @Transactional(readOnly = true)
    public List<WatchlistItemDto> getItems(Long userId, Long watchlistId, ListingType listingTypeFilter) {
        requireOwnedWatchlist(userId, watchlistId);

        List<WatchlistItem> items = watchlistItemRepository.findByWatchlistIdOrderByAddedAtDesc(watchlistId);
        Map<Long, Listing> listingsById = loadListingsById(items);

        return items.stream()
                .map(item -> enrichItem(item, listingsById.get(item.getListingId())))
                .filter(dto -> listingTypeFilter == null || dto.listingType() == listingTypeFilter)
                .toList();
    }

    @Override
    @Transactional
    public WatchlistItemDto addItem(Long userId, Long watchlistId, AddWatchlistItemRequest request) {
        requireOwnedWatchlist(userId, watchlistId);

        Listing listing = listingRepository.findById(request.listingId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Listing with id %s was not found.".formatted(request.listingId())));

        if (watchlistItemRepository.existsByWatchlistIdAndListingId(watchlistId, request.listingId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Listing with id %s is already in watchlist %s.".formatted(request.listingId(), watchlistId));
        }

        WatchlistItem item = new WatchlistItem();
        item.setWatchlistId(watchlistId);
        item.setListingId(request.listingId());
        item.setAddedAt(LocalDateTime.now(clock));

        return WatchlistItemDto.from(watchlistItemRepository.save(item), listing);
    }

    @Override
    @Transactional
    public void removeItem(Long userId, Long watchlistId, Long itemId) {
        requireOwnedWatchlist(userId, watchlistId);

        WatchlistItem item = watchlistItemRepository.findById(itemId)
                .orElseThrow(() -> itemNotFound(itemId));
        if (!item.getWatchlistId().equals(watchlistId)) {
            // Reported as NOT_FOUND so items of other watchlists are not leaked.
            throw itemNotFound(itemId);
        }
        watchlistItemRepository.delete(item);
    }

    /**
     * Loads one watchlist and asserts the caller owns it.
     *
     * @param userId caller identifier
     * @param watchlistId watchlist identifier
     * @return the owned watchlist
     */
    private Watchlist requireOwnedWatchlist(Long userId, Long watchlistId) {
        Watchlist watchlist = watchlistRepository.findById(watchlistId)
                .orElseThrow(() -> watchlistNotFound(watchlistId));
        if (!watchlist.getUserId().equals(userId)) {
            // Reported as NOT_FOUND so the existence of other users' watchlists is not leaked.
            throw watchlistNotFound(watchlistId);
        }
        return watchlist;
    }

    /**
     * Batch-loads the listings referenced by a set of watchlist items, indexed by listing id.
     *
     * @param items watchlist items whose listings are needed
     * @return id-indexed listing map
     */
    private Map<Long, Listing> loadListingsById(List<WatchlistItem> items) {
        List<Long> listingIds = items.stream()
                .map(WatchlistItem::getListingId)
                .distinct()
                .toList();
        return listingRepository.findAllById(listingIds).stream()
                .collect(Collectors.toMap(Listing::getId, Function.identity()));
    }

    /**
     * Enriches one watchlist item with the current market data of its listing.
     *
     * @param item watchlist item
     * @param listing current listing snapshot of the followed security
     * @return enriched response DTO
     */
    private WatchlistItemDto enrichItem(WatchlistItem item, Listing listing) {
        if (listing == null) {
            // A watchlist item references a listing that no longer exists; this
            // points to an inconsistency between the watchlist and the catalog.
            throw new IllegalStateException(
                    "Listing with id %s referenced by watchlist item %s was not found."
                            .formatted(item.getListingId(), item.getId()));
        }
        return WatchlistItemDto.from(item, listing);
    }

    /**
     * Validates and trims the requested watchlist name.
     *
     * @param name raw request value
     * @return normalized, non-blank watchlist name
     */
    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Watchlist name must not be blank.");
        }
        return normalized;
    }

    private ResponseStatusException watchlistNotFound(Long watchlistId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Watchlist with id %s was not found.".formatted(watchlistId));
    }

    private ResponseStatusException itemNotFound(Long itemId) {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Watchlist item with id %s was not found.".formatted(itemId));
    }
}
