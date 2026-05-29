package com.banka1.marketservice.stock.repository.influx;

import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Maps stock price feed snapshots to InfluxDB line protocol.
 */
final class StockPriceSnapshotLineProtocolMapper {

    private static final String MEASUREMENT = "stock_price_snapshot";

    private StockPriceSnapshotLineProtocolMapper() {
    }

    static String toLineProtocol(StockPriceSnapshotDto snapshot) {
        StringBuilder line = new StringBuilder(MEASUREMENT)
                .append(",ticker=").append(escapeTagValue(snapshot.getTicker()));

        if (hasText(snapshot.getCurrency())) {
            line.append(",currency=").append(escapeTagValue(snapshot.getCurrency()));
        }

        String fields = buildFields(snapshot);
        if (fields.isBlank()) {
            return "";
        }

        return line.append(' ')
                .append(fields)
                .append(' ')
                .append(toEpochNanos(snapshot.getTimestamp()))
                .toString();
    }

    private static String buildFields(StockPriceSnapshotDto snapshot) {
        StringBuilder fields = new StringBuilder();
        appendDecimalField(fields, "current_price", snapshot.getCurrentPrice());
        appendDecimalField(fields, "open_price", snapshot.getOpenPrice());
        appendDecimalField(fields, "previous_close", snapshot.getPreviousClose());
        appendDecimalField(fields, "change_percent", snapshot.getChangePercent());
        if (snapshot.getVolume() != null) {
            appendSeparator(fields);
            fields.append("volume=").append(snapshot.getVolume()).append('i');
        }
        return fields.toString();
    }

    private static void appendDecimalField(StringBuilder fields, String name, BigDecimal value) {
        if (value == null) {
            return;
        }
        appendSeparator(fields);
        fields.append(name).append('=').append(value.toPlainString());
    }

    private static void appendSeparator(StringBuilder fields) {
        if (!fields.isEmpty()) {
            fields.append(',');
        }
    }

    private static long toEpochNanos(Instant timestamp) {
        Instant effectiveTimestamp = timestamp == null ? Instant.now() : timestamp;
        return Math.addExact(
                Math.multiplyExact(effectiveTimestamp.getEpochSecond(), 1_000_000_000L),
                effectiveTimestamp.getNano()
        );
    }

    private static String escapeTagValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace(" ", "\\ ")
                .replace(",", "\\,")
                .replace("=", "\\=");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
