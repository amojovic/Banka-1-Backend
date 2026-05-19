package com.banka1.tradingservice.otc.dto;

import com.banka1.tradingservice.otc.domain.OtcRevisionAction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * WP-16 (Celina 4.2): read-model za jedan revizioni zapis iz istorije OTC pregovora
 * ({@code GET /otc/offers/{id}/history}).
 *
 * <p>Za {@link OtcRevisionAction#CREATE} su {@code old*} polja {@code null}; za
 * {@link OtcRevisionAction#COUNTER} popunjena su i {@code old*} i {@code new*};
 * za {@code ACCEPT}/{@code REJECT}/{@code WITHDRAW} {@code old*} ostaje {@code null}
 * a {@code new*} nosi vrednosti ponude u trenutku akcije (nema izmene vrednosti).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtcOfferRevisionDto {

    /** Primarni kljuc revizionog reda. */
    private Long id;

    /** ID OTC ponude na koju se zapis odnosi. */
    private Long offerId;

    /** Tip akcije (CREATE/COUNTER/ACCEPT/REJECT/WITHDRAW). */
    private OtcRevisionAction action;

    /** ID korisnika koji je izvrsio akciju. */
    private Long actorUserId;

    /** Citljivo ime aktora; {@code null} ako nije razreseno. */
    private String actorName;

    /** Uloga aktora u pregovoru: {@code BUYER} ili {@code SELLER}. */
    private String actorRole;

    /** Kolicina akcija pre izmene; {@code null} kad nema stare vrednosti. */
    private Integer oldAmount;

    /** Kolicina akcija posle izmene. */
    private Integer newAmount;

    /** Cena po akciji pre izmene; {@code null} kad nema stare vrednosti. */
    private BigDecimal oldPricePerStock;

    /** Cena po akciji posle izmene. */
    private BigDecimal newPricePerStock;

    /** Premija pre izmene; {@code null} kad nema stare vrednosti. */
    private BigDecimal oldPremium;

    /** Premija posle izmene. */
    private BigDecimal newPremium;

    /** Datum poravnanja pre izmene; {@code null} kad nema stare vrednosti. */
    private LocalDate oldSettlementDate;

    /** Datum poravnanja posle izmene. */
    private LocalDate newSettlementDate;

    /** Trenutak kreiranja revizionog reda. */
    private LocalDateTime createdAt;
}
