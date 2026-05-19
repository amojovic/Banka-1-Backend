package com.banka1.stock_service.controller;

import com.banka1.stock_service.domain.Listing;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.domain.Stock;
import com.banka1.stock_service.dto.StockDividendDataResponse;
import com.banka1.stock_service.repository.ListingRepository;
import com.banka1.stock_service.repository.StockRepository;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WP-14 (Celina 3.7): interni service-to-service endpoint koji izlaze podatke
 * potrebne za kvartalni obracun dividende.
 *
 * <p>Trading-service ({@code DividendDistributionService}) zove ovo jednom po
 * obracunu da dobije, za svaku STOCK hartiju, cenu na dan obracuna, valutu berze
 * i dividendnu stopu. Ti podaci zive u tri tabele u konsolidovanom
 * market-service-u ({@code listing}, {@code stock_exchange}, {@code stock}) kojima
 * trading-service nema direktan pristup, pa moraju preko REST-a.
 *
 * <p>Pristup je ogranicen na {@code SERVICE} ulogu — nije FE-facing.
 */
@RestController
@RequiredArgsConstructor
public class StockDividendInternalController {

    private final ListingRepository listingRepository;
    private final StockRepository stockRepository;

    /**
     * Vraca dividendnu projekciju za sve STOCK listing snapshot-e.
     *
     * <p>Za svaki listing tipa {@link ListingType#STOCK} spaja se odgovarajuci
     * {@link Stock} entitet (po ticker-u, case-insensitive) kako bi se dohvatila
     * {@code dividendYield}. Listinzi kojima nema sparenog stock-a se preskacu —
     * bez stock-a nema dividendne stope pa nema ni obracuna.
     *
     * @return lista {@link StockDividendDataResponse} redova, jedan po STOCK listingu
     */
    @Operation(summary = "Get per-stock dividend data (internal)")
    @GetMapping("/stocks/internal/dividend-data")
    @PreAuthorize("hasRole('SERVICE')")
    public List<StockDividendDataResponse> getDividendData() {
        Map<String, Stock> stocksByTicker = new HashMap<>();
        for (Stock stock : stockRepository.findAll()) {
            if (stock.getTicker() != null) {
                stocksByTicker.put(normalize(stock.getTicker()), stock);
            }
        }

        List<Listing> stockListings =
                listingRepository.findAllByListingTypeOrderByTickerAsc(ListingType.STOCK);

        return stockListings.stream()
                .map(listing -> toDividendData(listing, stocksByTicker.get(normalize(listing.getTicker()))))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private StockDividendDataResponse toDividendData(Listing listing, Stock stock) {
        if (stock == null) {
            return null;
        }
        String currency = listing.getStockExchange() != null
                ? listing.getStockExchange().getCurrency()
                : null;
        return new StockDividendDataResponse(
                listing.getId(),
                listing.getTicker(),
                listing.getPrice(),
                currency,
                stock.getDividendYield());
    }

    private String normalize(String ticker) {
        return ticker == null ? "" : ticker.trim().toUpperCase(Locale.ROOT);
    }
}
