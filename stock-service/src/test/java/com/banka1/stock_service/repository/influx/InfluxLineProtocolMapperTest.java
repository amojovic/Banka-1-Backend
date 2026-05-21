package com.banka1.stock_service.repository.influx;

import com.banka1.stock_service.domain.ListingType;
import com.banka1.stock_service.dto.ListingPricePoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InfluxLineProtocolMapper}.
 */
class InfluxLineProtocolMapperTest {

    @Test
    void toLineProtocolEscapesTagsAndFormatsFields() {
        ListingPricePoint point = new ListingPricePoint(
                42L,
                "BRK B,=TEST\\X",
                ListingType.STOCK,
                "XNYS MAIN",
                LocalDate.of(2026, 4, 8),
                new BigDecimal("212.40000000"),
                new BigDecimal("212.45000000"),
                new BigDecimal("212.35000000"),
                new BigDecimal("4.60000000"),
                25_000L
        );

        long timestamp = Instant.parse("2026-04-08T00:00:00Z").getEpochSecond() * 1_000_000_000L;

        assertThat(InfluxLineProtocolMapper.toLineProtocol(point))
                .isEqualTo("listing_price,listing_id=42,ticker=BRK\\ B\\,\\=TEST\\\\X,"
                        + "listing_type=STOCK,exchange_code=XNYS\\ MAIN "
                        + "price=212.40000000,ask=212.45000000,bid=212.35000000,"
                        + "change=4.60000000,volume=25000i "
                        + timestamp);
    }
}
