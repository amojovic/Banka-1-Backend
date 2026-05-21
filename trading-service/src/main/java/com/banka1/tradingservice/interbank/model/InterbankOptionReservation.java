package com.banka1.tradingservice.interbank.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tim 2 IMPORTANT-2 (PR_34): persist inter-bank OTC option reservation u DB
 * tabelu. Pre ovoga je {@code negotiationToReservation} ConcurrentHashMap u
 * {@link com.banka1.tradingservice.interbank.controller.InterbankOptionController}
 * — restart trading-service-a izmedju accept i exercise faze je gubio mapping
 * pa exercise tiho no-op-ovao na nasoj strani dok je partner imao rezervaciju
 * (orphan shares).
 *
 * <p>Status set: {@code RESERVED} (posle accept-a), {@code EXERCISED} (posle
 * exercise commit-a — terminalan), {@code RELEASED} (posle release-a —
 * terminalan).
 */
@Entity
@Table(name = "interbank_option_reservations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InterbankOptionReservation {

    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_EXERCISED = "EXERCISED";
    public static final String STATUS_RELEASED = "RELEASED";

    @Id
    @Column(name = "negotiation_id", length = 64, nullable = false)
    private String negotiationId;

    @Column(name = "reservation_id", length = 64, nullable = false)
    private String reservationId;

    @Column(nullable = false, length = 32)
    private String status;

    @Column(name = "seller_user_id")
    private Long sellerUserId;

    @Column(length = 32)
    private String ticker;

    private Integer quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
