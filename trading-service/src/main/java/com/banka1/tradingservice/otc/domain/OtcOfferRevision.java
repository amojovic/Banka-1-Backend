package com.banka1.tradingservice.otc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * WP-16 (Celina 4.2): jedan revizioni zapis u istoriji jednog OTC pregovora
 * (tabela {@code otc_offer_revisions}).
 *
 * <p>Spec (TODO_final, Celina 4): trenutno se vide samo aktivne ponude i nema
 * pregleda zavrsenih pregovora. {@code OtcService.counterOffer} prepisuje
 * {@link OtcOffer} red "u mestu" — istorija se gubi. Ovaj entitet cuva KOMPLETNU
 * istoriju svake akcije: stare i nove vrednosti, trenutak, i ko je promenio.
 *
 * <p>Jedan red = jedna akcija na ponudi. Persistuje ga {@code OtcService} u istoj
 * transakciji kao i mutacija ponude (snapshot-pre-izmene). Tabelu definise
 * Liquibase changeset {@code trading-otc/014-otc-offer-revisions.sql}; mapiranje
 * mora tacno odgovarati semi jer trading-service radi sa
 * {@code spring.jpa.hibernate.ddl-auto=validate}.
 *
 * <p>Za {@link OtcRevisionAction#CREATE} su {@code old*} polja {@code null} a
 * {@code new*} pocetne vrednosti; za {@link OtcRevisionAction#COUNTER} su
 * popunjena oba; za {@code ACCEPT}/{@code REJECT}/{@code WITHDRAW} value polja
 * mogu biti {@code null} (bez izmene vrednosti).
 */
@Entity
@Table(
        name = "otc_offer_revisions",
        indexes = {
                @Index(name = "idx_otc_offer_revisions_offer_id",   columnList = "offer_id"),
                @Index(name = "idx_otc_offer_revisions_created_at",  columnList = "created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OtcOfferRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID {@link OtcOffer} ponude na koju se zapis odnosi. */
    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    /** Tip akcije; cuva se kao {@link Enum#name()} string. */
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private OtcRevisionAction action;

    /** ID korisnika koji je izvrsio akciju. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    /** Citljivo ime aktora (razresheno preko client-service-a kad je dostupno). */
    @Column(name = "actor_name", length = 128)
    private String actorName;

    /** Uloga aktora u pregovoru: {@code BUYER} ili {@code SELLER}. */
    @Column(name = "actor_role", length = 16)
    private String actorRole;

    /** Kolicina akcija pre izmene; {@code null} za {@code CREATE}. */
    @Column(name = "old_amount")
    private Integer oldAmount;

    /** Kolicina akcija posle izmene. */
    @Column(name = "new_amount")
    private Integer newAmount;

    /** Cena po akciji pre izmene; {@code null} za {@code CREATE}. */
    @Column(name = "old_price_per_stock", precision = 19, scale = 2)
    private BigDecimal oldPricePerStock;

    /** Cena po akciji posle izmene. */
    @Column(name = "new_price_per_stock", precision = 19, scale = 2)
    private BigDecimal newPricePerStock;

    /** Premija pre izmene; {@code null} za {@code CREATE}. */
    @Column(name = "old_premium", precision = 19, scale = 2)
    private BigDecimal oldPremium;

    /** Premija posle izmene. */
    @Column(name = "new_premium", precision = 19, scale = 2)
    private BigDecimal newPremium;

    /** Datum poravnanja pre izmene; {@code null} za {@code CREATE}. */
    @Column(name = "old_settlement_date")
    private LocalDate oldSettlementDate;

    /** Datum poravnanja posle izmene. */
    @Column(name = "new_settlement_date")
    private LocalDate newSettlementDate;

    /** Trenutak kreiranja revizionog reda. */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
