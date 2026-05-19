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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WatchlistServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class WatchlistServiceImplTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-05-19T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private WatchlistRepository watchlistRepository;

    @Mock
    private WatchlistItemRepository watchlistItemRepository;

    @Mock
    private ListingRepository listingRepository;

    @Test
    void getWatchlistsForUserReturnsMappedDtosWithItemCount() {
        Watchlist watchlist = watchlist(1L, 7L, "Tech stocks");
        when(watchlistRepository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(watchlist));
        when(watchlistItemRepository.countByWatchlistId(1L)).thenReturn(3L);

        List<WatchlistDto> result = service().getWatchlistsForUser(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(7L);
        assertThat(result.get(0).name()).isEqualTo("Tech stocks");
        assertThat(result.get(0).itemCount()).isEqualTo(3L);
    }

    @Test
    void createWatchlistPersistsWatchlistForOwnerWithZeroItemCount() {
        when(watchlistRepository.save(any(Watchlist.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WatchlistDto created = service().createWatchlist(7L, new CreateWatchlistRequest("  Tech stocks  "));

        ArgumentCaptor<Watchlist> captor = ArgumentCaptor.forClass(Watchlist.class);
        verify(watchlistRepository).save(captor.capture());
        Watchlist saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getName()).isEqualTo("Tech stocks");
        assertThat(saved.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 19, 12, 0));
        assertThat(created.itemCount()).isZero();
    }

    @Test
    void deleteWatchlistRemovesItemsAndOwnedWatchlist() {
        Watchlist watchlist = watchlist(1L, 7L, "Tech stocks");
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist));

        service().deleteWatchlist(7L, 1L);

        verify(watchlistItemRepository).deleteByWatchlistId(1L);
        verify(watchlistRepository).delete(watchlist);
    }

    @Test
    void deleteWatchlistRejectsWatchlistOwnedByAnotherUser() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 99L, "Tech stocks")));

        assertThatThrownBy(() -> service().deleteWatchlist(7L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist with id 1 was not found");
        verify(watchlistRepository, never()).delete(any());
        verify(watchlistItemRepository, never()).deleteByWatchlistId(any());
    }

    @Test
    void deleteWatchlistRejectsMissingWatchlist() {
        when(watchlistRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().deleteWatchlist(7L, 404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist with id 404 was not found");
    }

    @Test
    void getItemsReturnsItemsEnrichedWithListingMarketData() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        WatchlistItem item = item(10L, 1L, 15L);
        when(watchlistItemRepository.findByWatchlistIdOrderByAddedAtDesc(1L)).thenReturn(List.of(item));
        when(listingRepository.findAllById(List.of(15L)))
                .thenReturn(List.of(listing(15L, "AAPL", "Apple Inc.", ListingType.STOCK)));

        List<WatchlistItemDto> result = service().getItems(7L, 1L, null);

        assertThat(result).hasSize(1);
        WatchlistItemDto dto = result.get(0);
        assertThat(dto.id()).isEqualTo(10L);
        assertThat(dto.watchlistId()).isEqualTo(1L);
        assertThat(dto.listingId()).isEqualTo(15L);
        assertThat(dto.ticker()).isEqualTo("AAPL");
        assertThat(dto.name()).isEqualTo("Apple Inc.");
        assertThat(dto.price()).isEqualByComparingTo("212.40");
        assertThat(dto.change()).isEqualByComparingTo("4.60");
        assertThat(dto.volume()).isEqualTo(25_000L);
        assertThat(dto.listingType()).isEqualTo(ListingType.STOCK);
    }

    @Test
    void getItemsAppliesListingTypeFilter() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Mixed")));
        WatchlistItem stockItem = item(10L, 1L, 15L);
        WatchlistItem forexItem = item(11L, 1L, 16L);
        when(watchlistItemRepository.findByWatchlistIdOrderByAddedAtDesc(1L))
                .thenReturn(List.of(stockItem, forexItem));
        when(listingRepository.findAllById(List.of(15L, 16L))).thenReturn(List.of(
                listing(15L, "AAPL", "Apple Inc.", ListingType.STOCK),
                listing(16L, "EURUSD", "Euro / US Dollar", ListingType.FOREX)));

        List<WatchlistItemDto> result = service().getItems(7L, 1L, ListingType.FOREX);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).listingType()).isEqualTo(ListingType.FOREX);
        assertThat(result.get(0).ticker()).isEqualTo("EURUSD");
    }

    @Test
    void getItemsFailsWhenAnItemReferencesADeletedListing() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        when(watchlistItemRepository.findByWatchlistIdOrderByAddedAtDesc(1L))
                .thenReturn(List.of(item(10L, 1L, 15L)));
        // The referenced listing is no longer in the catalog.
        when(listingRepository.findAllById(List.of(15L))).thenReturn(List.of());

        assertThatThrownBy(() -> service().getItems(7L, 1L, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Listing with id 15 referenced by watchlist item 10 was not found");
    }

    @Test
    void getItemsRejectsWatchlistOwnedByAnotherUser() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 99L, "Tech stocks")));

        assertThatThrownBy(() -> service().getItems(7L, 1L, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist with id 1 was not found");
    }

    @Test
    void addItemPersistsItemForOwnedWatchlist() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(15L, "AAPL", "Apple Inc.", ListingType.STOCK)));
        when(watchlistItemRepository.existsByWatchlistIdAndListingId(1L, 15L)).thenReturn(false);
        when(watchlistItemRepository.save(any(WatchlistItem.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WatchlistItemDto created = service().addItem(7L, 1L, new AddWatchlistItemRequest(15L));

        ArgumentCaptor<WatchlistItem> captor = ArgumentCaptor.forClass(WatchlistItem.class);
        verify(watchlistItemRepository).save(captor.capture());
        WatchlistItem saved = captor.getValue();
        assertThat(saved.getWatchlistId()).isEqualTo(1L);
        assertThat(saved.getListingId()).isEqualTo(15L);
        assertThat(saved.getAddedAt()).isEqualTo(LocalDateTime.of(2026, 5, 19, 12, 0));
        assertThat(created.ticker()).isEqualTo("AAPL");
        assertThat(created.listingType()).isEqualTo(ListingType.STOCK);
    }

    @Test
    void addItemRejectsUnknownListing() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        when(listingRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().addItem(7L, 1L, new AddWatchlistItemRequest(999L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Listing with id 999 was not found");
        verify(watchlistItemRepository, never()).save(any());
    }

    @Test
    void addItemRejectsListingAlreadyInWatchlist() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        when(listingRepository.findById(15L))
                .thenReturn(Optional.of(listing(15L, "AAPL", "Apple Inc.", ListingType.STOCK)));
        when(watchlistItemRepository.existsByWatchlistIdAndListingId(1L, 15L)).thenReturn(true);

        assertThatThrownBy(() -> service().addItem(7L, 1L, new AddWatchlistItemRequest(15L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("is already in watchlist");
        verify(watchlistItemRepository, never()).save(any());
    }

    @Test
    void addItemRejectsWatchlistOwnedByAnotherUser() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 99L, "Tech stocks")));

        assertThatThrownBy(() -> service().addItem(7L, 1L, new AddWatchlistItemRequest(15L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist with id 1 was not found");
        verify(watchlistItemRepository, never()).save(any());
    }

    @Test
    void removeItemDeletesItemBelongingToOwnedWatchlist() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        WatchlistItem item = item(10L, 1L, 15L);
        when(watchlistItemRepository.findById(10L)).thenReturn(Optional.of(item));

        service().removeItem(7L, 1L, 10L);

        verify(watchlistItemRepository).delete(item);
    }

    @Test
    void removeItemRejectsItemBelongingToAnotherWatchlist() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        WatchlistItem item = item(10L, 2L, 15L);
        when(watchlistItemRepository.findById(10L)).thenReturn(Optional.of(item));

        assertThatThrownBy(() -> service().removeItem(7L, 1L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist item with id 10 was not found");
        verify(watchlistItemRepository, never()).delete(any());
    }

    @Test
    void removeItemRejectsMissingItem() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 7L, "Tech stocks")));
        when(watchlistItemRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().removeItem(7L, 1L, 404L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist item with id 404 was not found");
    }

    @Test
    void removeItemRejectsWatchlistOwnedByAnotherUser() {
        when(watchlistRepository.findById(1L)).thenReturn(Optional.of(watchlist(1L, 99L, "Tech stocks")));

        assertThatThrownBy(() -> service().removeItem(7L, 1L, 10L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watchlist with id 1 was not found");
        verify(watchlistItemRepository, never()).delete(any());
    }

    private WatchlistServiceImpl service() {
        return new WatchlistServiceImpl(watchlistRepository, watchlistItemRepository, listingRepository, FIXED_CLOCK);
    }

    private Watchlist watchlist(Long id, Long userId, String name) {
        Watchlist watchlist = new Watchlist();
        watchlist.setId(id);
        watchlist.setUserId(userId);
        watchlist.setName(name);
        watchlist.setCreatedAt(LocalDateTime.of(2026, 5, 19, 12, 0));
        return watchlist;
    }

    private WatchlistItem item(Long id, Long watchlistId, Long listingId) {
        WatchlistItem item = new WatchlistItem();
        item.setId(id);
        item.setWatchlistId(watchlistId);
        item.setListingId(listingId);
        item.setAddedAt(LocalDateTime.of(2026, 5, 19, 12, 0));
        return item;
    }

    private Listing listing(Long id, String ticker, String name, ListingType listingType) {
        Listing listing = new Listing();
        listing.setId(id);
        listing.setSecurityId(id);
        listing.setListingType(listingType);
        listing.setTicker(ticker);
        listing.setName(name);
        listing.setLastRefresh(LocalDateTime.of(2026, 5, 19, 10, 0));
        listing.setPrice(new BigDecimal("212.40"));
        listing.setAsk(new BigDecimal("212.50"));
        listing.setBid(new BigDecimal("212.30"));
        listing.setChange(new BigDecimal("4.60"));
        listing.setVolume(25_000L);
        return listing;
    }
}
