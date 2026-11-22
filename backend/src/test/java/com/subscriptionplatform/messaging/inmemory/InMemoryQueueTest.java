package com.subscriptionplatform.messaging.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.subscriptionplatform.messaging.QueueDepth;
import com.subscriptionplatform.messaging.ReceivedMessage;
import com.subscriptionplatform.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** The SQS semantics the pipeline relies on, implemented by the in-memory queue. */
class InMemoryQueueTest {

    private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-02T08:00:00Z"));
    private final InMemoryQueue deadLetterQueue = new InMemoryQueue("dlq", VISIBILITY_TIMEOUT, null, clock);
    private final InMemoryQueue queue = new InMemoryQueue("orders", VISIBILITY_TIMEOUT,
            new InMemoryQueue.RedrivePolicy(deadLetterQueue, 3), clock);

    @Test
    void aReceivedMessageStaysHiddenUntilItsVisibilityTimeoutExpires() {
        queue.send("order-1", Map.of());

        assertThat(receiveOne().receiveCount()).isEqualTo(1);
        assertThat(queue.receive(10, Duration.ZERO)).isEmpty();
        assertThat(queue.depth()).isEqualTo(new QueueDepth(0, 1));

        clock.advance(VISIBILITY_TIMEOUT);

        assertThat(receiveOne().receiveCount()).isEqualTo(2);
    }

    @Test
    void acknowledgingDeletesTheMessage() {
        queue.send("order-1", Map.of());

        queue.acknowledge(receiveOne());
        clock.advance(VISIBILITY_TIMEOUT);

        assertThat(queue.receive(10, Duration.ZERO)).isEmpty();
        assertThat(queue.size()).isZero();
    }

    @Test
    void onlyTheLatestReceiptHandleCanAcknowledge() {
        queue.send("order-1", Map.of());
        ReceivedMessage stale = receiveOne();
        clock.advance(VISIBILITY_TIMEOUT);
        ReceivedMessage current = receiveOne();

        queue.acknowledge(stale);
        assertThat(queue.size()).isEqualTo(1);

        queue.acknowledge(current);
        assertThat(queue.size()).isZero();
    }

    @Test
    void retryAfterMakesTheMessageVisibleAgainAfterTheDelay() {
        queue.send("order-1", Map.of());
        queue.retryAfter(receiveOne(), Duration.ofSeconds(4));

        clock.advance(Duration.ofSeconds(3));
        assertThat(queue.receive(10, Duration.ZERO)).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assertThat(receiveOne().receiveCount()).isEqualTo(2);
    }

    @Test
    void afterMaxReceiveCountReceivesTheMessageMovesToTheDeadLetterQueue() {
        String messageId = queue.send("order-1", Map.of("eventType", "OrderCreated"));

        for (int delivery = 1; delivery <= 3; delivery++) {
            ReceivedMessage message = receiveOne();
            assertThat(message.receiveCount()).isEqualTo(delivery);
            queue.retryAfter(message, Duration.ZERO);
        }

        // The fourth receive finds the message exhausted and redrives it instead of delivering it.
        assertThat(queue.receive(10, Duration.ZERO)).isEmpty();
        assertThat(queue.size()).isZero();
        assertThat(deadLetterQueue.snapshot(10)).singleElement().satisfies(deadLetter -> {
            assertThat(deadLetter.messageId()).isEqualTo(messageId);
            assertThat(deadLetter.body()).isEqualTo("order-1");
            assertThat(deadLetter.attributes()).containsEntry("eventType", "OrderCreated");
        });
    }

    @Test
    void deliversInSendOrderAndHonoursTheBatchSize() {
        for (int i = 1; i <= 5; i++) {
            queue.send("order-" + i, Map.of());
        }

        List<ReceivedMessage> batch = queue.receive(3, Duration.ZERO);

        assertThat(batch).extracting(ReceivedMessage::body).containsExactly("order-1", "order-2", "order-3");
        assertThat(queue.depth()).isEqualTo(new QueueDepth(2, 3));
    }

    @Test
    void longPollingReturnsAsSoonAsAMessageArrives() throws Exception {
        CompletableFuture<List<ReceivedMessage>> poll =
                CompletableFuture.supplyAsync(() -> queue.receive(10, Duration.ofSeconds(10)));
        Thread.sleep(100);

        queue.send("order-1", Map.of());

        assertThat(poll.get(5, TimeUnit.SECONDS)).extracting(ReceivedMessage::body).containsExactly("order-1");
    }

    @Test
    void longPollingGivesUpAfterTheWaitTime() {
        long start = System.nanoTime();

        assertThat(queue.receive(10, Duration.ofMillis(150))).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(140));
    }

    private ReceivedMessage receiveOne() {
        List<ReceivedMessage> messages = queue.receive(1, Duration.ZERO);
        assertThat(messages).hasSize(1);
        return messages.get(0);
    }
}
