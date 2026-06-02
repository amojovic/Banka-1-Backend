package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Watchlist;
import com.banka1.stock_service.domain.WatchlistItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Persistence tests for {@link WatchlistItemRepository}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WatchlistItemRepositoryTest {

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Autowired
    private WatchlistItemRepository watchlistItemRepository;

    @Test
    void shouldPersistAndLoadWatchlistItemNewestFirst() {
        Long watchlistId = persistWatchlist(5L, "Tech stocks");

        WatchlistItem older = createItem(watchlistId, 11L, LocalDateTime.of(2026, 5, 1, 10, 0));
        WatchlistItem newer = createItem(watchlistId, 22L, LocalDateTime.of(2026, 5, 10, 10, 0));
        watchlistItemRepository.saveAndFlush(older);
        watchlistItemRepository.saveAndFlush(newer);

        List<WatchlistItem> result = watchlistItemRepository.findByWatchlistIdOrderByAddedAtDesc(watchlistId);

        assertEquals(2, result.size());
        assertEquals(22L, result.get(0).getListingId());
        assertEquals(11L, result.get(1).getListingId());
    }

    @Test
    void countByWatchlistIdReturnsItemCountForThatWatchlistOnly() {
        Long firstWatchlistId = persistWatchlist(5L, "Tech stocks");
        Long secondWatchlistId = persistWatchlist(5L, "Forex pairs");

        watchlistItemRepository.saveAndFlush(createItem(firstWatchlistId, 11L, LocalDateTime.now()));
        watchlistItemRepository.saveAndFlush(createItem(firstWatchlistId, 22L, LocalDateTime.now()));
        watchlistItemRepository.saveAndFlush(createItem(secondWatchlistId, 33L, LocalDateTime.now()));

        assertEquals(2, watchlistItemRepository.countByWatchlistId(firstWatchlistId));
        assertEquals(1, watchlistItemRepository.countByWatchlistId(secondWatchlistId));
    }

    @Test
    void existsByWatchlistIdAndListingIdDetectsAlreadyFollowedListing() {
        Long watchlistId = persistWatchlist(5L, "Tech stocks");
        watchlistItemRepository.saveAndFlush(createItem(watchlistId, 11L, LocalDateTime.now()));

        assertTrue(watchlistItemRepository.existsByWatchlistIdAndListingId(watchlistId, 11L));
        assertFalse(watchlistItemRepository.existsByWatchlistIdAndListingId(watchlistId, 99L));
    }

    @Test
    void uniqueConstraintRejectsSameListingTwiceInOneWatchlist() {
        Long watchlistId = persistWatchlist(5L, "Tech stocks");
        watchlistItemRepository.saveAndFlush(createItem(watchlistId, 11L, LocalDateTime.now()));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> watchlistItemRepository.saveAndFlush(createItem(watchlistId, 11L, LocalDateTime.now()))
        );
    }

    @Test
    void deleteByWatchlistIdRemovesEveryItemOfThatWatchlist() {
        Long watchlistId = persistWatchlist(5L, "Tech stocks");
        watchlistItemRepository.saveAndFlush(createItem(watchlistId, 11L, LocalDateTime.now()));
        watchlistItemRepository.saveAndFlush(createItem(watchlistId, 22L, LocalDateTime.now()));

        watchlistItemRepository.deleteByWatchlistId(watchlistId);

        assertEquals(0, watchlistItemRepository.countByWatchlistId(watchlistId));
    }

    private Long persistWatchlist(Long userId, String name) {
        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setName(name);
        watchlist.setCreatedAt(LocalDateTime.of(2026, 5, 19, 12, 0));
        return watchlistRepository.saveAndFlush(watchlist).getId();
    }

    private WatchlistItem createItem(Long watchlistId, Long listingId, LocalDateTime addedAt) {
        WatchlistItem item = new WatchlistItem();
        item.setWatchlistId(watchlistId);
        item.setListingId(listingId);
        item.setAddedAt(addedAt);
        return item;
    }
}
