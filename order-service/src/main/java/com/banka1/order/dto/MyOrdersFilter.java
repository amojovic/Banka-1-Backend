package com.banka1.order.dto;

import com.banka1.order.entity.enums.ListingType;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;

import java.time.LocalDateTime;

/**
 * Optional filter criteria for the "Moji orderi" order-history screen.
 *
 * <p>Every field is nullable; a null value means "do not filter on this dimension".
 * The {@code from}/{@code to} bounds are inclusive and matched against the order's
 * creation timestamp.
 *
 * @param status      restrict to a single order status, or null for any
 * @param direction   restrict to BUY or SELL, or null for any
 * @param listingType restrict to a single security category, or null for any
 * @param from        inclusive lower bound on the creation timestamp, or null
 * @param to          inclusive upper bound on the creation timestamp, or null
 */
public record MyOrdersFilter(OrderStatus status,
                             OrderDirection direction,
                             ListingType listingType,
                             LocalDateTime from,
                             LocalDateTime to) {

    /** An empty filter that matches every order owned by the user. */
    public static MyOrdersFilter none() {
        return new MyOrdersFilter(null, null, null, null, null);
    }

    /** Whether a security-type filter is active and listing lookups must drive filtering. */
    public boolean hasListingTypeFilter() {
        return listingType != null;
    }
}
