package com.banka1.order.entity;

import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "recurring_orders")
@Getter
@Setter
@NoArgsConstructor
public class RecurringOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OrderDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringMode mode;

    /**
     * Quoted because {@code value} is reserved in H2 (used by {@code @DataJpaTest} slice).
     * The Liquibase changeset quotes it to match.
     */
    @Column(name = "\"value\"", nullable = false, precision = 19, scale = 4)
    private BigDecimal value;

    @Column(nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecurringCadence cadence;

    @Column(nullable = false)
    private LocalDateTime nextRun;

    @Column(nullable = false)
    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
