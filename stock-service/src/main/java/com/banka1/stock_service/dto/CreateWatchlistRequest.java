package com.banka1.stock_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a watchlist (Celina 3.4).
 *
 * <p>The owner ({@code userId}) is not part of the request — it is derived
 * server-side from the caller's JWT {@code id} claim.
 *
 * @param name human-readable watchlist name; a non-blank value of at most
 *             128 characters is required
 */
public record CreateWatchlistRequest(
        @NotBlank @Size(max = 128) String name
) {
}
