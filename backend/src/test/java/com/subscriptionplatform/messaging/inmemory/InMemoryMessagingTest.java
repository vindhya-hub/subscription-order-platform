package com.subscriptionplatform.messaging.inmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.subscriptionplatform.messaging.DeadLetterMessage;
import com.subscriptionplatform.messaging.OutboundMessage;
import com.subscriptionplatform.messaging.ReceivedMessage;
import com.subscriptionplatform.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Topic to queue fan-out and dead-letter redrive, wired like localstack/init-aws.sh. */
class InMemoryMessagingTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-03-02T08:00:00Z"));
    private final InMemoryMessaging messaging = InMemoryMessaging.create(Duration.ofSeconds(30), 3, clock);

    @Test
    void publishingToTheTopicDeliversToTheSubscribedQueue() {
        messaging.topic().publish(new OutboundMessage("{\"orderId\":1}", Map.of("eventType", "OrderCreated")));

        assertThat(messaging.queue().receive(10, Duration.ZERO)).singleElement().satisfies(message -> {
            assertThat(message.body()).isEqualTo("{\"orderId\":1}");
            assertThat(message.attributes()).containsEntry("eventType", "OrderCreated");
            assertThat(message.receiveCount()).isEqualTo(1);
        });
    }

    @Test
    void theQueueReportsTheRedriveThreshold() {
        assertThat(messaging.queue().maxReceiveCount()).isEqualTo(3);
    }

    @Test
    void peekingDoesNotRemoveDeadLetters() {
        deadLetter("order-1");

        assertThat(messaging.deadLetters().peek(10)).hasSize(1);
        assertThat(messaging.deadLetters().peek(10)).hasSize(1);
        assertThat(messaging.deadLetters().size()).isEqualTo(1);
    }

    @Test
    void redrivingSendsTheMessageBackWithAFreshReceiveCount() {
        deadLetter("order-1");

        List<DeadLetterMessage> moved = messaging.deadLetters().redrive(Set.of());

        assertThat(moved).extracting(DeadLetterMessage::body).containsExactly("order-1");
        assertThat(messaging.deadLetters().size()).isZero();
        List<ReceivedMessage> redelivered = messaging.queue().receive(10, Duration.ZERO);
        assertThat(redelivered).singleElement().satisfies(message -> {
            assertThat(message.body()).isEqualTo("order-1");
            assertThat(message.receiveCount()).isEqualTo(1);
        });
    }

    @Test
    void theBeforeResendHookRunsBeforeTheMessageCanBeReceivedAgain() {
        deadLetter("order-1");
        List<Integer> queuedWhenHookRan = new ArrayList<>();

        messaging.deadLetters().redrive(Set.of(), message -> queuedWhenHookRan.add(messaging.queue().size()));

        assertThat(queuedWhenHookRan).containsExactly(0);
        assertThat(messaging.queue().size()).isEqualTo(1);
    }

    @Test
    void aFailingHookLeavesTheMessageInTheDeadLetterQueue() {
        deadLetter("order-1");

        assertThatIllegalStateException().isThrownBy(() ->
                messaging.deadLetters().redrive(Set.of(), message -> {
                    throw new IllegalStateException("database down");
                }));

        assertThat(messaging.deadLetters().size()).isEqualTo(1);
        assertThat(messaging.queue().size()).isZero();
    }

    @Test
    void redrivingSelectedMessagesLeavesTheOthersInTheDeadLetterQueue() {
        deadLetter("order-1");
        deadLetter("order-2");
        String second = messaging.deadLetters().peek(10).get(1).messageId();

        List<DeadLetterMessage> moved = messaging.deadLetters().redrive(Set.of(second));

        assertThat(moved).extracting(DeadLetterMessage::body).containsExactly("order-2");
        assertThat(messaging.deadLetters().peek(10)).extracting(DeadLetterMessage::body).containsExactly("order-1");
    }

    /** Sends a message and fails it three times so the fourth receive dead-letters it. */
    private void deadLetter(String body) {
        messaging.topic().publish(new OutboundMessage(body, Map.of()));
        for (int delivery = 1; delivery <= 3; delivery++) {
            ReceivedMessage message = messaging.queue().receive(1, Duration.ZERO).get(0);
            messaging.queue().retryAfter(message, Duration.ZERO);
        }
        assertThat(messaging.queue().receive(10, Duration.ZERO)).isEmpty();
    }
}
