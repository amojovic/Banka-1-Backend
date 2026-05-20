package com.banka1.tradingservice.otc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "otc_negotiation_history", indexes = {
        @Index(name = "idx_otc_history_offer_id", columnList = "offer_id"),
        @Index(name = "idx_otc_history_buyer_id", columnList = "buyer_id"),
        @Index(name = "idx_otc_history_seller_id", columnList = "seller_id"),
        @Index(name = "idx_otc_history_changed_at", columnList = "changed_at"),
        @Index(name = "idx_otc_history_new_status", columnList = "new_status")
})
@Getter
@Setter
public class OtcNegotiationHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "offer_id", nullable = false)
    private Long offerId;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "seller_id", nullable = false)
    private Long sellerId;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_name", length = 128)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private OtcNegotiationEventType eventType;

    @Column(name = "stock_ticker", nullable = false, length = 16)
    private String stockTicker;

    @Column(name = "old_amount")
    private Integer oldAmount;

    @Column(name = "new_amount")
    private Integer newAmount;

    @Column(name = "old_price_per_stock", precision = 19, scale = 2)
    private BigDecimal oldPricePerStock;

    @Column(name = "new_price_per_stock", precision = 19, scale = 2)
    private BigDecimal newPricePerStock;

    @Column(name = "old_premium", precision = 19, scale = 2)
    private BigDecimal oldPremium;

    @Column(name = "new_premium", precision = 19, scale = 2)
    private BigDecimal newPremium;

    @Column(name = "old_settlement_date")
    private LocalDate oldSettlementDate;

    @Column(name = "new_settlement_date")
    private LocalDate newSettlementDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_status", length = 24)
    private OtcOfferStatus oldStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", length = 24)
    private OtcOfferStatus newStatus;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt = LocalDateTime.now();
}
