package com.banka1.marketservice.stock.repository.influx;

import com.banka1.marketservice.stock.config.MarketInfluxProperties;
import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;
import com.banka1.marketservice.stock.repository.StockPriceSnapshotHistoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Repository;
import org.springframework.web.client.RestClient;

/**
 * InfluxDB-backed history store for market-service stock price feed snapshots.
 */
@Repository
@Slf4j
public class InfluxStockPriceSnapshotHistoryStore implements StockPriceSnapshotHistoryStore {

    private static final String WRITE_PATH = "/api/v2/write";

    private final MarketInfluxProperties properties;
    private final RestClient influxDbRestClient;

    /**
     * Creates an InfluxDB writer for stock price snapshots.
     *
     * @param properties InfluxDB configuration
     */
    public InfluxStockPriceSnapshotHistoryStore(MarketInfluxProperties properties) {
        this.properties = properties;
        this.influxDbRestClient = RestClient.builder()
                .baseUrl(properties.url())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Token " + properties.token())
                .build();
    }

    @Override
    public void saveSnapshot(StockPriceSnapshotDto snapshot) {
        if (!isEnabled() || snapshot == null || !hasText(snapshot.getTicker())) {
            return;
        }

        String lineProtocol = StockPriceSnapshotLineProtocolMapper.toLineProtocol(snapshot);
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
            log.warn("InfluxDB stock price snapshot write failed: {}", exception.getMessage());
        }
    }

    private boolean isEnabled() {
        return properties.enabled()
                && hasText(properties.url())
                && hasText(properties.org())
                && hasText(properties.bucket())
                && hasText(properties.token());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
