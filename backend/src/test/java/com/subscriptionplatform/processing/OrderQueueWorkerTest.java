package com.subscriptionplatform.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.subscriptionplatform.messaging.MessagingProperties;
import com.subscriptionplatform.messaging.ReceivedMessage;
import com.subscriptionplatform.messaging.inmemory.InMemoryMessaging;
import com.subscriptionplatform.messaging.inmemory.InMemoryQueue;
import com.subscriptionplatform.processing.OrderMessageHandler.Disposition;
import com.subscriptionplatform.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How the worker turns a handler decision into queue operations. */
class OrderQueueWorkerTest {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration BACKOFF = Duration.ofSeconds(2);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-02T08:00:00Z"));
    private final InMemoryMessaging messaging = InMemoryMessaging.create(VISIBILITY_TIMEOUT, 3, clock);
    private final InMemoryQueue queue = messaging.queue();
    private final OrderMessageHandler handler = mock(OrderMessageHandler.class);
    private final OrderQueueWorker worker = new OrderQueueWorker(queue, handler, new MessagingProperties(
            MessagingProperties.Provider.IN_MEMORY, 3, VISIBILITY_TIMEOUT, BACKOFF,
            new MessagingProperties.Consumer(true, 10, Duration.ZERO),
            new MessagingProperties.Aws(null, null, null, null, null, null, null, null)));

    @Test
    void acknowledgedMessagesAreDeleted() {
        queue.send("order-1", Map.of());
        when(handler.handle(any(ReceivedMessage.class), anyInt())).thenReturn(Disposition.ACKNOWLEDGE);

        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);

        assertThat(queue.size()).isZero();
    }

    @Test
    void retriesBackOffLinearlyWithTheReceiveCount() {
        queue.send("order-1", Map.of());
        when(handler.handle(any(ReceivedMessage.class), anyInt())).thenReturn(Disposition.RETRY);

        worker.pollOnce(Duration.ZERO);
        clock.advance(BACKOFF.minusMillis(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);

        // Second failure: 2 x back-off.
        clock.advance(BACKOFF.multipliedBy(2).minusMillis(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isZero();
        clock.advance(Duration.ofMillis(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
    }

    @Test
    void deadLetteredMessagesAreReleasedSoTheRedrivePolicyMovesThem() {
        queue.send("order-1", Map.of());
        when(handler.handle(any(ReceivedMessage.class), anyInt())).thenReturn(Disposition.DEAD_LETTER);

        for (int delivery = 1; delivery <= 3; delivery++) {
            assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        }

        assertThat(worker.pollOnce(Duration.ZERO)).isZero();
        assertThat(messaging.deadLetters().size()).isEqualTo(1);
    }

    @Test
    void anUnexpectedErrorLeavesTheMessageForRedeliveryAfterTheVisibilityTimeout() {
        queue.send("order-1", Map.of());
        when(handler.handle(any(ReceivedMessage.class), anyInt())).thenThrow(new IllegalStateException("db down"));

        worker.pollOnce(Duration.ZERO);
        clock.advance(VISIBILITY_TIMEOUT.minusSeconds(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
    }
}
