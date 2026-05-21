package com.banka1.stock_service.repository.influx;

import com.banka1.stock_service.config.StockInfluxProperties;
import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingPricePoint;
import com.banka1.stock_service.repository.ListingPriceHistoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * InfluxDB-backed repository for listing price history.
 *
 * <p>The implementation deliberately degrades to no-op reads and writes when disabled or when
 * InfluxDB is temporarily unavailable. Postgres remains the fallback for user-facing history reads.
 */
@Repository
@Slf4j
public class InfluxListingPriceHistoryStore implements ListingPriceHistoryStore {

    private static final String WRITE_PATH = "/api/v2/write";
    private static final String QUERY_PATH = "/api/v2/query";
    private static final String MEASUREMENT = "listing_price";

    private final RestClient influxDbRestClient;
    private final StockInfluxProperties properties;

    /**
     * Creates an InfluxDB listing history store.
     *
     * @param influxDbRestClient HTTP client configured for InfluxDB
     * @param properties InfluxDB market-data properties
     */
    public InfluxListingPriceHistoryStore(
            @Qualifier("influxDbRestClient") RestClient influxDbRestClient,
            StockInfluxProperties properties
    ) {
        this.influxDbRestClient = influxDbRestClient;
        this.properties = properties;
    }

    @Override
    public void saveDailySnapshots(Collection<ListingPricePoint> points) {
        if (!isEnabled() || points == null || points.isEmpty()) {
            return;
        }

        String lineProtocol = points.stream()
                .filter(Objects::nonNull)
                .map(InfluxLineProtocolMapper::toLineProtocol)
                .collect(Collectors.joining("\n"));

        if (lineProtocol.isBlank()) {
            return;
        }

        try {
            influxDbRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path(WRITE_PATH)
                            .queryParam("org", properties.org())
                            .queryParam("bucket", properties.bucket())
                            .queryParam("precision", "ns")
                            .build())
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(lineProtocol)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException exception) {
            log.warn("InfluxDB market-data write failed: {}", exception.getMessage());
        }
    }

    @Override
    public List<ListingPricePoint> findDailySnapshots(Long listingId, LocalDate startDate) {
        if (!isEnabled() || listingId == null) {
            return List.of();
        }

        try {
            String response = influxDbRestClient.post()
                    .uri(uriBuilder -> uriBuilder.path(QUERY_PATH)
                            .queryParam("org", properties.org())
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.valueOf("application/csv"))
                    .body(Map.of(
                            "query", buildFluxQuery(listingId, startDate),
                            "type", "flux"
                    ))
                    .retrieve()
                    .body(String.class);

            return parseAnnotatedCsv(response, listingId);
        } catch (RuntimeException exception) {
            log.warn("InfluxDB market-data query failed for listingId={}: {}", listingId, exception.getMessage());
            return List.of();
        }
    }

    private boolean isEnabled() {
        return properties.enabled()
                && hasText(properties.url())
                && hasText(properties.org())
                && hasText(properties.bucket())
                && hasText(properties.token());
    }

    private String buildFluxQuery(Long listingId, LocalDate startDate) {
        Instant start = startDate == null
                ? Instant.EPOCH
                : startDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        return """
                from(bucket: "%s")
                  |> range(start: %s)
                  |> filter(fn: (r) => r._measurement == "%s")
                  |> filter(fn: (r) => r.listing_id == "%s")
                  |> pivot(rowKey: ["_time"], columnKey: ["_field"], valueColumn: "_value")
                  |> keep(columns: ["_time", "price", "ask", "bid", "change", "volume"])
                  |> sort(columns: ["_time"])
                """.formatted(
                escapeFluxString(properties.bucket()),
                start,
                MEASUREMENT,
                listingId
        );
    }

    private List<ListingPricePoint> parseAnnotatedCsv(String response, Long listingId) {
        if (response == null || response.isBlank()) {
            return List.of();
        }

        List<ListingPricePoint> points = new ArrayList<>();
        List<String> header = null;
        for (String line : response.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }

            List<String> cells = parseCsvLine(line);
            if (cells.contains("_time") && cells.contains("price")) {
                header = cells;
                continue;
            }
            if (header == null) {
                continue;
            }

            Map<String, String> row = toRow(header, cells);
            points.add(toPoint(row, listingId));
        }

        return points.stream()
                .sorted(Comparator.comparing(ListingPricePoint::date))
                .toList();
    }

    private ListingPricePoint toPoint(Map<String, String> row, Long listingId) {
        LocalDate date = Instant.parse(row.get("_time")).atZone(ZoneOffset.UTC).toLocalDate();
        return new ListingPricePoint(
                listingId,
                "",
                ListingType.STOCK,
                "",
                date,
                new BigDecimal(row.get("price")),
                new BigDecimal(row.get("ask")),
                new BigDecimal(row.get("bid")),
                new BigDecimal(row.get("change")),
                new BigDecimal(row.get("volume")).longValue()
        );
    }

    private Map<String, String> toRow(List<String> header, List<String> cells) {
        Map<String, String> row = new HashMap<>();
        for (int index = 0; index < header.size() && index < cells.size(); index++) {
            row.put(header.get(index), cells.get(index));
        }
        return row;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (character == ',' && !quoted) {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(character);
            }
        }
        cells.add(cell.toString());
        return cells;
    }

    private String escapeFluxString(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

}
