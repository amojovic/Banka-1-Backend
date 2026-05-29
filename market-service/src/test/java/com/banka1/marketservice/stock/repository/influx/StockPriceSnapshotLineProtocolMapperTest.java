package com.banka1.marketservice.stock.repository.influx;

import com.banka1.marketservice.stock.dto.StockPriceSnapshotDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StockPriceSnapshotLineProtocolMapperTest {

    @Test
    void toLineProtocolEscapesTagsAndFormatsFields() {
        StockPriceSnapshotDto snapshot = StockPriceSnapshotDto.builder()
                .ticker("BRK B,=TEST\\X")
                .currentPrice(new BigDecimal("212.40000000"))
                .openPrice(new BigDecimal("210.00000000"))
                .previousClose(new BigDecimal("207.80000000"))
                .changePercent(new BigDecimal("2.2100"))
                .volume(25_000L)
                .currency("US D")
                .timestamp(Instant.parse("2026-04-08T12:30:15.123456789Z"))
                .build();

        assertThat(StockPriceSnapshotLineProtocolMapper.toLineProtocol(snapshot))
                .isEqualTo("stock_price_snapshot,ticker=BRK\\ B\\,\\=TEST\\\\X,currency=US\\ D "
                        + "current_price=212.40000000,open_price=210.00000000,"
                        + "previous_close=207.80000000,change_percent=2.2100,volume=25000i "
                        + "1775651415123456789");
    }
}
