package com.banka1.stock_service.repository.influx;

import com.banka1.stock_service.dto.ListingPricePoint;

import java.math.BigDecimal;
import java.time.ZoneOffset;

/**
 * Maps listing market-data points to InfluxDB line protocol.
 */
final class InfluxLineProtocolMapper {

    private static final String MEASUREMENT = "listing_price";

    private InfluxLineProtocolMapper() {
    }

    static String toLineProtocol(ListingPricePoint point) {
        return MEASUREMENT
                + ",listing_id=" + escapeTagValue(String.valueOf(point.listingId()))
                + ",ticker=" + escapeTagValue(point.ticker())
                + ",listing_type=" + escapeTagValue(point.listingType().name())
                + ",exchange_code=" + escapeTagValue(point.exchangeCode())
                + " price=" + decimal(point.price())
                + ",ask=" + decimal(point.ask())
                + ",bid=" + decimal(point.bid())
                + ",change=" + decimal(point.change())
                + ",volume=" + point.volume() + "i"
                + " " + toEpochNanos(point);
    }

    private static String decimal(BigDecimal value) {
        return value.toPlainString();
    }

    private static long toEpochNanos(ListingPricePoint point) {
        long epochSecond = point.date()
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC)
                .getEpochSecond();
        return Math.multiplyExact(epochSecond, 1_000_000_000L);
    }

    private static String escapeTagValue(String value) {
        return value
                .replace("\\", "\\\\")
                .replace(" ", "\\ ")
                .replace(",", "\\,")
                .replace("=", "\\=");
    }
}
