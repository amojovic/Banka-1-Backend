package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.Watchlist;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Persistence tests for {@link WatchlistRepository}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WatchlistRepositoryTest {

    @Autowired
    private WatchlistRepository watchlistRepository;

    @Test
    void shouldPersistAndLoadWatchlistWithAllFields() {
        Watchlist watchlist = createWatchlist(5L, "Tech stocks", LocalDateTime.of(2026, 5, 19, 12, 0));

        Watchlist saved = watchlistRepository.saveAndFlush(watchlist);

        Watchlist persisted = watchlistRepository.findById(saved.getId()).orElseThrow();
        assertNotNull(persisted.getId());
        assertEquals(5L, persisted.getUserId());
        assertEquals("Tech stocks", persisted.getName());
        assertEquals(LocalDateTime.of(2026, 5, 19, 12, 0), persisted.getCreatedAt());
    }

    @Test
    void findByUserIdOrderByCreatedAtDescReturnsOnlyOwnerWatchlistsNewestFirst() {
        Watchlist older = createWatchlist(7L, "Forex pairs", LocalDateTime.of(2026, 5, 1, 10, 0));
        Watchlist newer = createWatchlist(7L, "Tech stocks", LocalDateTime.of(2026, 5, 10, 10, 0));
        Watchlist otherUser = createWatchlist(99L, "Other", LocalDateTime.of(2026, 5, 5, 10, 0));

        watchlistRepository.saveAndFlush(older);
        watchlistRepository.saveAndFlush(newer);
        watchlistRepository.saveAndFlush(otherUser);

        List<Watchlist> result = watchlistRepository.findByUserIdOrderByCreatedAtDesc(7L);

        assertEquals(2, result.size());
        assertEquals("Tech stocks", result.get(0).getName());
        assertEquals("Forex pairs", result.get(1).getName());
    }

    private Watchlist createWatchlist(Long userId, String name, LocalDateTime createdAt) {
        Watchlist watchlist = new Watchlist();
        watchlist.setUserId(userId);
        watchlist.setName(name);
        watchlist.setCreatedAt(createdAt);
        return watchlist;
    }
}
