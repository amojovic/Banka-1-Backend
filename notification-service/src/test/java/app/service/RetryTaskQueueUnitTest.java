package app.service;

import app.dto.RetryTask;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link RetryTaskQueue}.
 *
 * <p>This class verifies the in-memory retry scheduling behavior used by notification-service.
 * The tests cover invalid input handling, deduplication, ordering, and stale-entry cleanup so
 * retry execution remains deterministic.
 */
class RetryTaskQueueUnitTest {

    /**
     * Verifies that scheduling is ignored when the delivery identifier is missing.
     *
     * <p>This protects the retry queue from malformed tasks that could never be matched back to
     * a persisted delivery record.
     */
    @Test
    void scheduleWithNullDeliveryIdIsIgnored() {
        RetryTaskQueue queue = new RetryTaskQueue();
        queue.schedule(null, Instant.now());
        assertNull(queue.peek());
    }

    /**
     * Verifies that scheduling is ignored when the next-attempt timestamp is missing.
     *
     * <p>A retry task without a due time is invalid, so the queue must reject it instead of
     * storing unusable state.
     */
    @Test
    void scheduleWithNullNextAttemptAtIsIgnored() {
        RetryTaskQueue queue = new RetryTaskQueue();
        queue.schedule("delivery-1", null);
        assertNull(queue.peek());
    }

    /**
     * Verifies that rescheduling the exact same delivery for the exact same time
     * does not create duplicate due entries.
     *
     * <p>This keeps retry processing idempotent when multiple code paths try to enqueue the same
     * task state.
     */
    @Test
    void scheduleSameDeliveryIdWithSameTimestampDoesNotAddDuplicateEntry() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant t = Instant.parse("2026-03-07T12:00:05Z");

        queue.schedule("delivery-1", t);
        queue.schedule("delivery-1", t);

        Instant afterT = t.plusSeconds(1);
        RetryTask first = queue.pollDue(afterT);
        assertNotNull(first);
        assertNull(queue.pollDue(afterT));
    }

    /**
     * Verifies that polling an empty queue returns {@code null}.
     *
     * <p>This documents the queue contract for callers that drive retry processing loops.
     */
    @Test
    void pollDueOnEmptyQueueReturnsNull() {
        RetryTaskQueue queue = new RetryTaskQueue();
        assertNull(queue.pollDue(Instant.now()));
    }

    /**
     * Verifies that stale queued entries for the same delivery are discarded in favor of the
     * latest scheduled attempt.
     *
     * <p>This matters because deliveries can be rescheduled multiple times, and only the newest
     * timestamp should survive as the actionable retry task.
     */
    @Test
    void pollDueDiscardsMultipleStaleEntriesAndReturnsCurrentOne() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant stale1 = Instant.parse("2026-03-07T12:00:01Z");
        Instant stale2 = Instant.parse("2026-03-07T12:00:02Z");
        Instant current = Instant.parse("2026-03-07T12:00:05Z");

        queue.schedule("delivery-1", stale1);
        queue.schedule("delivery-1", stale2);
        queue.schedule("delivery-1", current);

        Instant afterAll = current.plusSeconds(1);
        RetryTask polled = queue.pollDue(afterAll);
        assertNotNull(polled);
        assertEquals(current, polled.nextAttemptAt());
        assertNull(queue.pollDue(afterAll));
    }

    /**
     * Verifies that the queue exposes the earliest due task first.
     *
     * <p>This protects the priority-ordering semantics required by the retry scheduler.
     */
    @Test
    void queueReturnsEarliestTaskFirst() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant base = Instant.parse("2026-03-07T12:00:00Z");

        queue.schedule("delivery-late", base.plusSeconds(10));
        queue.schedule("delivery-early", base.plusSeconds(5));

        RetryTask head = queue.peek();
        assertEquals("delivery-early", head.deliveryId());

        assertNull(queue.pollDue(base.plusSeconds(4)));
        assertEquals("delivery-early", queue.pollDue(base.plusSeconds(5)).deliveryId());
        assertEquals("delivery-late", queue.pollDue(base.plusSeconds(10)).deliveryId());
    }

    /**
     * Verifies that an older queued entry is ignored when the same delivery is rescheduled for a
     * newer time.
     *
     * <p>This prevents the retry worker from processing outdated attempts after delivery state
     * has already moved forward.
     */
    @Test
    void queueIgnoresStaleEntryWhenSameDeliveryIsRescheduled() {
        RetryTaskQueue queue = new RetryTaskQueue();
        Instant firstAttempt = Instant.parse("2026-03-07T12:00:05Z");
        Instant secondAttempt = Instant.parse("2026-03-07T12:00:10Z");

        queue.schedule("delivery-1", firstAttempt);
        queue.schedule("delivery-1", secondAttempt);

        assertNull(queue.pollDue(firstAttempt));
        assertEquals("delivery-1", queue.pollDue(secondAttempt).deliveryId());
    }
}
