package com.banka1.tradingservice.funds.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WP-18 (Celina 4.4): jedna tacka serije istorijske vrednosti fonda.
 *
 * <p>Koristi se za:
 * <ul>
 *   <li>{@code GET /funds/{id}/value-history} — stvarna serija jednog fonda
 *       (grafikon istorijske vrednosti fonda);</li>
 *   <li>{@code GET /funds/value-history/average} — sistemski prosek
 *       {@code totalValue}-a po datumu preko svih fondova ({@code fundId=null},
 *       poredbeni grafikon).</li>
 * </ul>
 */
@Data
@Builder
public class FundValueSnapshotDto {

    /** ID fonda; {@code null} za sistemsku prosecnu seriju. */
    private Long fundId;

    /** Datum snapshot-a. */
    private LocalDate snapshotDate;

    /** Vrednost fonda na taj datum (RSD); za prosecnu seriju — prosek preko fondova. */
    private BigDecimal totalValue;

    /** Profit fonda na taj datum (RSD); za prosecnu seriju — prosek preko fondova. */
    private BigDecimal profit;
}
