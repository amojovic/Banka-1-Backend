package com.banka1.order.repository;

import com.banka1.order.entity.Order;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Repository for {@link Order} entities.
 */
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    /**
     * Loads an order row under a write lock so execution/cancel cannot mutate it concurrently.
     *
     * @param orderId the order identifier
     * @return locked order when present
    */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :orderId")
    Optional<Order> findByIdForUpdate(@Param("orderId") Long orderId);

    /**
     * Returns all orders placed by a specific user.
     *
     * @param userId the user's identifier
     * @return list of orders for that user
     */
    List<Order> findByUserId(Long userId);

    /**
     * Returns all orders with a given status.
     *
     * @param status the order status to filter by
     * @return list of matching orders
     */
    List<Order> findByStatus(OrderStatus status);

    /**
     * Returns all orders for a specific user with a given status.
     *
     * @param userId the user's identifier
     * @param status the order status to filter by
     * @return list of matching orders
     */
    List<Order> findByUserIdAndStatus(Long userId, OrderStatus status);

    List<Order> findByDirection(OrderDirection direction);

    List<Order> findByUserIdAndDirection(Long userId, OrderDirection direction);

    List<Order> findByUserIdIn(Set<Long> userIds);

    /**
     * Izvedena bank-held kolicina: zbir izvrsenih kolicina BUY naloga kojima je
     * {@code purchaseFor = BANK} za zadatog drzaoca i listing.
     *
     * <p>Koristi se u {@code DividendPayoutExecutor} za odredjivanje koliko
     * drzaocevog portfolija je u ime banke (oporezovan 0%) naspram licnog (15%).
     * Izvrsena kolicina = {@code quantity - remainingPortions} — kada je nalog
     * kompletno izveden {@code remainingPortions = 0}. Nalozi koji jos nisu
     * dirnuti (PENDING/PENDING_CONFIRMATION) doprinose 0 jer im je
     * {@code remainingPortions = quantity}.
     *
     * <p>Rezultat se stegne na {@code [0, holder.quantity]} u pozivaocu.
     *
     * @param userId    ID drzaoca (aktuar koji kupuje za banku)
     * @param listingId ID listinga u stock-service-u
     * @return zbir izvrsenih bank-held BUY kolicina, 0 ako nema takvih naloga
     */
    @Query("SELECT COALESCE(SUM(o.quantity - o.remainingPortions), 0) FROM Order o "
            + "WHERE o.userId = :userId AND o.listingId = :listingId "
            + "AND o.purchaseFor = com.banka1.order.entity.enums.PurchaseFor.BANK "
            + "AND o.direction = com.banka1.order.entity.enums.OrderDirection.BUY")
    long bankHeldBuyQuantity(@Param("userId") Long userId, @Param("listingId") Long listingId);
}
