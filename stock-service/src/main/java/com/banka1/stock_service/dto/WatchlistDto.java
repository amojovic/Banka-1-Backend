package com.banka1.stock_service.dto;

import com.banka1.stock_service.domain.Watchlist;

import java.time.LocalDateTime;

/**
 * API response view of a {@link Watchlist} (Celina 3.4).
 *
 * @param id technical identifier of the watchlist
 * @param userId owner identifier
 * @param name human-readable watchlist name
 * @param itemCount number of followed securities currently in the watchlist
 * @param createdAt creation timestamp
 */
public record WatchlistDto(
        Long id,
        Long userId,
        String name,
        long itemCount,
        LocalDateTime createdAt
) {

    /**
     * Maps a persisted {@link Watchlist} entity to its API response view.
     *
     * @param watchlist persisted entity
     * @param itemCount number of followed securities in the watchlist
     * @return response DTO
     */
    public static WatchlistDto from(Watchlist watchlist, long itemCount) {
        return new WatchlistDto(
                watchlist.getId(),
                watchlist.getUserId(),
                watchlist.getName(),
                itemCount,
                watchlist.getCreatedAt()
        );
    }
}
