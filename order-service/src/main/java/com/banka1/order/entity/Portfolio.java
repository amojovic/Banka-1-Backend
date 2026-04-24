package com.banka1.order.entity;

import com.banka1.order.entity.enums.ListingType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Represents a single security position held by a user.
 * Tracks quantity, average purchase price, and optional OTC visibility (stocks only).
 * Each record is identified by the combination of userId and listingId.
 *
 * <h2>Quantity model</h2>
 *
 * A position tracks three independent quantity dimensions that together describe
 * how many units the owner holds, how many are currently tied up in open
 * commitments, and how many are advertised for OTC discovery:
 *
 * <ul>
 *   <li><b>{@code quantity}</b> - total units currently owned by the user.
 *       This is the authoritative inventory figure and is decremented only when a
 *       sale settles (exchange execution or OTC option exercise), not when an
 *       order is placed.</li>
 *
 *   <li><b>{@code reservedQuantity}</b> - units already committed to pending
 *       obligations that have not yet settled. Reservations are consumed when
 *       the obligation completes and released on cancel/expire/reject. Sources
 *       of reservations include:
 *       <ul>
 *         <li>SELL orders on the exchange between creation and full execution
 *             (Phase 3).</li>
 *         <li>Active OTC option contracts where this user is the seller,
 *             from contract signing until exercise or expiry (Phase 4).</li>
 *       </ul>
 *       The number of units free for a new reservation is always
 *       {@code quantity - reservedQuantity}. This field is owned by the order
 *       and OTC flows; end users do not set it directly.</li>
 *
 *   <li><b>{@code publicQuantity}</b> - units the owner has voluntarily
 *       advertised on the OTC market so other participants can initiate an
 *       option negotiation against them (Phase 4). This is a display/discovery
 *       flag, not a commitment: making units public does not reserve them, and
 *       conversely reserving units does not hide them from the public listing.
 *       The user controls this value via the "make public" action and it is
 *       meaningful only for {@link ListingType#STOCK} positions and only when
 *       {@link #isPublic} is {@code true}.</li>
 * </ul>
 *
 * <h3>Invariants and interaction</h3>
 *
 * <ul>
 *   <li>{@code 0 <= reservedQuantity <= quantity} at all times.</li>
 *   <li>{@code 0 <= publicQuantity <= quantity}.</li>
 *   <li>{@code publicQuantity} and {@code reservedQuantity} may overlap: the
 *       same unit can be both publicly listed and reserved by an in-flight OTC
 *       option. Do not sum these two fields when computing availability.</li>
 *   <li>Units actually free for a new OTC reservation or exchange SELL order:
 *       {@code quantity - reservedQuantity}. The OTC service must refuse to
 *       accept an option whose size exceeds this figure even if
 *       {@code publicQuantity} is larger.</li>
 * </ul>
 *
 * <h3>Phase 4 (OTC) extension notes</h3>
 *
 * Before Phase 4 lands, {@code reservedQuantity} is written exclusively by
 * exchange SELL flows. When OTC option contracts are introduced, the same
 * field must be incremented on contract signing (seller side) and decremented
 * on exercise or expiry; the semantics above are chosen so that no additional
 * field is needed.
 */
@Entity
@Table(name = "portfolio",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "listing_id"}))
@Getter
@Setter
@NoArgsConstructor
public class Portfolio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ID of the user (client) who owns this position. */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** ID of the security listing in stock-service. */
    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    /** Type of the held security (STOCK, FUTURES, FOREX, OPTION). */
    @Enumerated(EnumType.STRING)
    @Column(name = "listing_type", nullable = false)
    private ListingType listingType;

    /**
     * Total number of units currently held by the user.
     * Decremented only on settlement (exchange fill or OTC option exercise),
     * never on order placement. See the class Javadoc for the full quantity model.
     */
    @Column(nullable = false)
    private Integer quantity;

    /**
     * Units committed to open obligations and therefore unavailable for new
     * reservations. Incremented when a SELL order is placed on the exchange or
     * (Phase 4) when an OTC option contract is signed with this user as seller;
     * decremented when the obligation settles or is released
     * (cancel / expire / reject). Units free for a new reservation are always
     * {@code quantity - reservedQuantity}.
     * <p>
     * Independent of {@link #publicQuantity}: an OTC-public unit may also be
     * reserved. Do not sum the two fields when computing availability.
     */
    @Column(nullable = false)
    private Integer reservedQuantity = 0;

    /** Weighted average price at which units were purchased, in RSD. */
    @Column(name = "average_purchase_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal averagePurchasePrice;

    /**
     * Whether this position is publicly visible for OTC trading.
     * Only applicable to STOCK positions; always {@code false} for other types.
     * Set by the owner via the "make public" action on the portfolio page and
     * kept in sync with {@link #publicQuantity} (see its Javadoc).
     */
    @Column(name = "is_public", nullable = false)
    private Boolean isPublic = false;

    /**
     * Number of units the owner has voluntarily advertised on the OTC market
     * so other participants can discover this position and initiate an option
     * negotiation against it (Phase 4). This is a discovery signal only; it is
     * <em>not</em> a reservation and does not itself reduce availability. Units
     * actually free for a new OTC reservation are always
     * {@code quantity - reservedQuantity} and never {@code publicQuantity}.
     * <p>
     * Meaningful only when {@link #isPublic} is {@code true}. Independent of
     * {@link #reservedQuantity}: the same unit may be simultaneously public
     * and reserved (e.g., advertised for OTC while a SELL order is in flight).
     */
    @Column(name = "public_quantity", nullable = false)
    private Integer publicQuantity = 0;

    /** Timestamp of the last change to this position. Updated automatically on every save. */
    @Column(name = "last_modified", nullable = false)
    private LocalDateTime lastModified;

    /** Updates the lastModified timestamp on every persist and update. */
    @PrePersist
    @PreUpdate
    public void updateLastModified() {
        this.lastModified = LocalDateTime.now();
    }
}
