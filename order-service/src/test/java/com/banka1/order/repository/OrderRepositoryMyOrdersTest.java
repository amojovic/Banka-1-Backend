package com.banka1.order.repository;

import com.banka1.order.entity.Order;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import com.banka1.order.entity.enums.OrderType;
import com.banka1.ordertestsupport.OrderSliceTestConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-10: Spring Data slice test for the "Moji orderi" history query.
 *
 * <p>Verifies the nullable-param filter behavior backing the {@code /orders/my-orders}
 * endpoint: scoping to a user, each filter dimension, combined filters, the inclusive
 * date range, and paging. H2 in PostgreSQL mode (see {@code application-test.properties}),
 * Liquibase disabled — Hibernate builds the schema from the {@code Order} mapping.
 *
 * <p>WP-27e: the original repository carried a {@code findMyOrders(...)} JPQL query that
 * used {@code (:p IS NULL OR o.col = :p)} predicates. PostgreSQL rejects that with
 * "could not determine data type of parameter $N" because it cannot infer the type of
 * a nullable parameter consumed only inside an {@code IS NULL} comparison. The
 * production fix moved the query to a {@link Specification} built in
 * {@code OrderCreationServiceImpl.buildMyOrdersSpec}; this test exercises the same
 * predicate logic via a local {@link #myOrdersSpec(Long, OrderStatus, OrderDirection,
 * LocalDateTime, LocalDateTime)} helper that mirrors the production spec exactly.
 *
 * <p>{@code Order.createdAt} carries {@code @CreationTimestamp}, so Hibernate stamps it
 * with the current time on every insert regardless of any value set on the entity. To
 * exercise historical date ranges, each row's {@code created_at} column is rewritten
 * with a native UPDATE after persistence and the context is cleared so a fresh read
 * reflects the override.
 */
@DataJpaTest
@ContextConfiguration(classes = OrderSliceTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OrderRepositoryMyOrdersTest {

    @Autowired
    private OrderRepository repository;

    @PersistenceContext
    private EntityManager entityManager;

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt").nullsLast());

    /**
     * Mirrors {@code OrderCreationServiceImpl.buildMyOrdersSpec} exactly so this slice test
     * exercises the same predicate logic the production service uses against PostgreSQL.
     * Each filter dimension is appended only when its argument is non-null, which is the
     * point of moving off the JPQL {@code (:p IS NULL OR …)} pattern.
     */
    private static Specification<Order> myOrdersSpec(Long userId,
                                                    OrderStatus status,
                                                    OrderDirection direction,
                                                    LocalDateTime from,
                                                    LocalDateTime to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("userId"), userId));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (direction != null) {
                predicates.add(cb.equal(root.get("direction"), direction));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.<LocalDateTime>get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.<LocalDateTime>get("createdAt"), to));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Order newOrder(Long userId, OrderStatus status, OrderDirection direction, OrderType orderType) {
        Order order = new Order();
        order.setUserId(userId);
        order.setListingId(42L);
        order.setOrderType(orderType);
        order.setQuantity(10);
        order.setContractSize(1);
        order.setPricePerUnit(new BigDecimal("100.00"));
        order.setLimitValue(orderType == OrderType.LIMIT ? new BigDecimal("95.00") : null);
        order.setDirection(direction);
        order.setStatus(status);
        order.setIsDone(status == OrderStatus.DONE);
        order.setLastModification(LocalDateTime.now());
        order.setRemainingPortions(10);
        order.setAfterHours(false);
        order.setExchangeClosed(false);
        order.setAllOrNone(false);
        order.setMargin(false);
        order.setAccountId(5L);
        order.setReservedLimitExposure(BigDecimal.ZERO);
        return order;
    }

    /**
     * Persists an order and forces its {@code created_at} to the given value, working
     * around the {@code @CreationTimestamp} that would otherwise stamp the current time.
     * A null {@code createdAt} models a legacy row that was never backfilled. The context
     * is cleared so a subsequent repository query reads the overridden value from the DB.
     */
    private Long persistWithCreatedAt(Order order, LocalDateTime createdAt) {
        entityManager.persist(order);
        entityManager.flush();
        Long id = order.getId();
        entityManager.createNativeQuery("UPDATE orders SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", createdAt)
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();
        return id;
    }

    @Test
    void scopesResultsToTheGivenUserOnly() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.APPROVED, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 10, 9, 0));
        persistWithCreatedAt(newOrder(2L, OrderStatus.APPROVED, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 10, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, null, null, null, null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getUserId()).isEqualTo(1L);
    }

    @Test
    void nullFiltersReturnEveryOrderOfTheUser() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 1, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DECLINED, OrderDirection.SELL, OrderType.LIMIT),
                LocalDateTime.of(2026, 5, 2, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, null, null, null, null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void filtersByStatus() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 1, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.PENDING, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 2, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, OrderStatus.DONE, null, null, null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getStatus()).isEqualTo(OrderStatus.DONE);
    }

    @Test
    void filtersByDirection() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.APPROVED, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 1, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.APPROVED, OrderDirection.SELL, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 2, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, null, OrderDirection.SELL, null, null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getDirection()).isEqualTo(OrderDirection.SELL);
    }

    @Test
    void filtersByInclusiveCreatedAtRange() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 4, 30, 23, 59));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 1, 0, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 31, 23, 59, 59));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 6, 1, 0, 1));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, null, null,
                        LocalDateTime.of(2026, 5, 1, 0, 0),
                        LocalDateTime.of(2026, 5, 31, 23, 59, 59)),
                PageRequest.of(0, 20, NEWEST_FIRST));

        // Both boundary rows are inclusive; the 30 Apr and 1 Jun rows are excluded.
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void dateRangeWithOnlyLowerBoundExcludesRowsWithNullCreatedAt() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET), null);
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 10, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, null, null, LocalDateTime.of(2026, 5, 1, 0, 0), null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent().getFirst().getCreatedAt()).isNotNull();
    }

    @Test
    void combinesStatusDirectionAndDateRange() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.SELL, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 15, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 15, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.PENDING, OrderDirection.SELL, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 15, 9, 0));
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.SELL, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 25, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(1L, OrderStatus.DONE, OrderDirection.SELL,
                        LocalDateTime.of(2026, 5, 10, 0, 0),
                        LocalDateTime.of(2026, 5, 20, 0, 0)),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isEqualTo(1);
        Order match = page.getContent().getFirst();
        assertThat(match.getStatus()).isEqualTo(OrderStatus.DONE);
        assertThat(match.getDirection()).isEqualTo(OrderDirection.SELL);
        assertThat(match.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 5, 15, 9, 0));
    }

    @Test
    void pagesAndSortsNewestFirst() {
        for (int day = 1; day <= 5; day++) {
            persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                    LocalDateTime.of(2026, 5, day, 9, 0));
        }

        Page<Order> firstPage = repository.findAll(
                myOrdersSpec(1L, null, null, null, null),
                PageRequest.of(0, 2, NEWEST_FIRST));

        assertThat(firstPage.getTotalElements()).isEqualTo(5);
        assertThat(firstPage.getTotalPages()).isEqualTo(3);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(firstPage.getContent().get(0).getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 5, 5, 9, 0));
        assertThat(firstPage.getContent().get(1).getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 5, 4, 9, 0));

        Page<Order> lastPage = repository.findAll(
                myOrdersSpec(1L, null, null, null, null),
                PageRequest.of(2, 2, NEWEST_FIRST));

        assertThat(lastPage.getContent()).hasSize(1);
        assertThat(lastPage.getContent().getFirst().getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 0));
    }

    @Test
    void emptyResultWhenUserHasNoOrders() {
        persistWithCreatedAt(newOrder(1L, OrderStatus.DONE, OrderDirection.BUY, OrderType.MARKET),
                LocalDateTime.of(2026, 5, 1, 9, 0));

        Page<Order> page = repository.findAll(
                myOrdersSpec(999L, null, null, null, null),
                PageRequest.of(0, 20, NEWEST_FIRST));

        assertThat(page.getTotalElements()).isZero();
        assertThat(page.getContent()).isEmpty();
    }

    @Test
    void creationTimestampIsPopulatedOnInsert() {
        // Sanity check on the @CreationTimestamp mapping itself.
        Order order = newOrder(1L, OrderStatus.PENDING, OrderDirection.BUY, OrderType.MARKET);
        entityManager.persist(order);
        entityManager.flush();

        assertThat(order.getCreatedAt()).isNotNull();
    }
}
