package com.banka1.stock_service.repository;

import com.banka1.stock_service.domain.ListingDailyPriceInfo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Repository for persisting and querying {@link ListingDailyPriceInfo} entities.
 */
public interface ListingDailyPriceInfoRepository extends JpaRepository<ListingDailyPriceInfo, Long> {

    /**
     * Finds one historical daily snapshot by listing id and date.
     *
     * @param listingId listing id
     * @param date trading day
     * @return matching historical snapshot if present
     */
    Optional<ListingDailyPriceInfo> findByListingIdAndDate(Long listingId, LocalDate date);

    /**
     * Loads all historical daily snapshots for one listing ordered by date ascending.
     *
     * @param listingId listing id
     * @return daily history for the provided listing
     */
    List<ListingDailyPriceInfo> findAllByListingIdOrderByDateAsc(Long listingId);
}
