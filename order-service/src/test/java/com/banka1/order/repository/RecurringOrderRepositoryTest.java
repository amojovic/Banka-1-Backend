package com.banka1.order.repository;

import com.banka1.order.entity.RecurringOrder;
import com.banka1.order.entity.enums.OrderDirection;
import com.banka1.order.entity.enums.RecurringCadence;
import com.banka1.order.entity.enums.RecurringMode;
import com.banka1.ordertestsupport.OrderSliceTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WP-13: Spring Data slice test for {@link RecurringOrderRepository}.
 *
 * <p>Verifies the standing-order persistence mapping and the two query methods
 * that back the controller ({@code findByUserIdOrderByCreatedAtDesc}) and the
 * scheduler ({@code findDue}). H2 in PostgreSQL mode, Liquibase disabled —
 * Hibernate builds the schema from the {@code RecurringOrder} mapping.
 */
@DataJpaTest
@ContextConfiguration(classes = OrderSliceTestConfiguration.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringOrderRepositoryTest {

    @Autowired
    private RecurringOrderRepository repository;

    private RecurringOrder newRecurringOrder(Long userId, boolean active, LocalDateTime nextRun) {
        RecurringOrder order = new RecurringOrder();
        order.setUserId(userId);
        order.setListingId(42L);
        order.setDirection(OrderDirection.BUY);
        order.setMode(RecurringMode.BY_AMOUNT);
        order.setValue(new BigDecimal("10000.0000"));
        order.setAccountId(5L);
        order.setCadence(RecurringCadence.MONTHLY);
        order.setNextRun(nextRun);
        order.setActive(active);
        return order;
    }

    @Test
    void persistsAndReadsBackAllFields() {
        RecurringOrder saved = repository.save(
                newRecurringOrder(1L, true, LocalDateTime.of(2026, 6, 1, 0, 0)));

        RecurringOrder reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getUserId()).isEqualTo(1L);
        assertThat(reloaded.getListingId()).isEqualTo(42L);
        assertThat(reloaded.getDirection()).isEqualTo(OrderDirection.BUY);
        assertThat(reloaded.getMode()).isEqualTo(RecurringMode.BY_AMOUNT);
        assertThat(reloaded.getValue()).isEqualByComparingTo("10000.0000");
        assertThat(reloaded.getAccountId()).isEqualTo(5L);
        assertThat(reloaded.getCadence()).isEqualTo(RecurringCadence.MONTHLY);
        assertThat(reloaded.getNextRun()).isEqualTo(LocalDateTime.of(2026, 6, 1, 0, 0));
        assertThat(reloaded.getActive()).isTrue();
    }

    @Test
    void creationTimestampIsPopulatedOnInsert() {
        RecurringOrder saved = repository.save(
                newRecurringOrder(1L, true, LocalDateTime.of(2026, 6, 1, 0, 0)));

        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void findByUserIdScopesToOwnerNewestFirst() {
        repository.save(newRecurringOrder(1L, true, LocalDateTime.of(2026, 6, 1, 0, 0)));
        repository.save(newRecurringOrder(1L, false, LocalDateTime.of(2026, 7, 1, 0, 0)));
        repository.save(newRecurringOrder(2L, true, LocalDateTime.of(2026, 6, 1, 0, 0)));

        List<RecurringOrder> result = repository.findByUserIdOrderByCreatedAtDesc(1L);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(r -> r.getUserId().equals(1L));
    }

    @Test
    void findDueReturnsOnlyActiveOrdersWithNextRunInThePast() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 12, 0);
        RecurringOrder duePast = repository.save(
                newRecurringOrder(1L, true, now.minusHours(1)));
        // active but in the future -> not due
        repository.save(newRecurringOrder(1L, true, now.plusHours(1)));
        // due timestamp but paused -> not due
        repository.save(newRecurringOrder(1L, false, now.minusHours(1)));

        List<RecurringOrder> due = repository.findDue(now);

        assertThat(due).extracting(RecurringOrder::getId).containsExactly(duePast.getId());
    }

    @Test
    void findDueIncludesOrderWhoseNextRunEqualsNow() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 12, 0);
        RecurringOrder exactlyDue = repository.save(newRecurringOrder(1L, true, now));

        List<RecurringOrder> due = repository.findDue(now);

        assertThat(due).extracting(RecurringOrder::getId).containsExactly(exactlyDue.getId());
    }

    @Test
    void findDueIsEmptyWhenNothingIsDue() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 1, 12, 0);
        repository.save(newRecurringOrder(1L, true, now.plusDays(5)));

        assertThat(repository.findDue(now)).isEmpty();
    }
}
