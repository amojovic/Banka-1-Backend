package com.banka1.tradingservice.dividend.dto;

import com.banka1.tradingservice.dividend.domain.DividendPayout;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * WP-14 (Celina 3.7): read-only projekcija {@link DividendPayout}-a za
 * {@code GET /dividends} (istorija primljenih dividendi na portfolio strani).
 *
 * @param id           identifikator isplate
 * @param userId       ID drzaoca
 * @param stockTicker  ticker hartije
 * @param listingId    ID listinga
 * @param quantity     broj jedinica na dan obracuna
 * @param grossAmount  bruto iznos (pre poreza), u valuti listinga
 * @param currency     valuta isplate
 * @param taxAmountRsd iznos poreza u RSD (0 za pozicije banke)
 * @param netAmount    neto iznos isplacen drzaocu, u valuti listinga
 * @param accountId    ID racuna na koji je isplaceno, ili {@code null}
 * @param paymentDate  datum isplate
 * @param forBank      {@code true} ako je pozicija drzana u ime banke
 */
public record DividendPayoutDto(
        Long id,
        Long userId,
        String stockTicker,
        Long listingId,
        Integer quantity,
        BigDecimal grossAmount,
        String currency,
        BigDecimal taxAmountRsd,
        BigDecimal netAmount,
        Long accountId,
        LocalDate paymentDate,
        boolean forBank
) {

    /**
     * Mapira {@link DividendPayout} entitet u DTO.
     *
     * @param entity entitet iz baze
     * @return DTO projekcija
     */
    public static DividendPayoutDto from(DividendPayout entity) {
        return new DividendPayoutDto(
                entity.getId(),
                entity.getUserId(),
                entity.getStockTicker(),
                entity.getListingId(),
                entity.getQuantity(),
                entity.getGrossAmount(),
                entity.getCurrency(),
                entity.getTaxAmountRsd(),
                entity.getNetAmount(),
                entity.getAccountId(),
                entity.getPaymentDate(),
                entity.isForBank());
    }
}
