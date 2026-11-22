package com.subscriptionplatform.processing;

import static com.subscriptionplatform.support.TestClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;

import com.subscriptionplatform.billing.BillingService;
import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.messaging.OutboundMessage;
import com.subscriptionplatform.messaging.inmemory.InMemoryMessaging;
import com.subscriptionplatform.operations.DeadLetter;
import com.subscriptionplatform.operations.OperationsService;
import com.subscriptionplatform.operations.QueueStats;
import com.subscriptionplatform.operations.ReplayResult;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.order.OrderStatus;
import com.subscriptionplatform.outbox.OutboxEventRepository;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.support.ManualPipelineTest;
import com.subscriptionplatform.support.MutableClock;
import com.subscriptionplatform.support.TestClockConfiguration;
import com.subscriptionplatform.support.TestData;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retry, dead-lettering and replay on the in-memory SNS/SQS implementation, one receive at a time. The
 * failure injector makes every attempt fail, so the order exhausts maxReceiveCount (3) and the redrive policy
 * moves its message to the DLQ.
 */
@ManualPipelineTest
class InMemoryRetryDeadLetterIT {

    private static final Duration BACKOFF = Duration.ofSeconds(2);

    @Autowired
    private BillingService billing;
    @Autowired
    private OrderQueueWorker worker;
    @Autowired
    private OperationsService operations;
    @Autowired
    private FailureInjector failureInjector;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private InMemoryMessaging messaging;
    @Autowired
    private MutableClock clock;
    @Autowired
    private TestData data;

    private Customer customer;
    private Plan coffee;
    private Plan tea;

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfiguration.START);
        data.reset();
        customer = data.customer("Ava Patel");
        coffee = data.plan("COFFEE", "16.50");
        tea = data.plan("TEA", "22.00");
    }

    @Test
    void aFailingOrderIsRetriedThenDeadLetteredAfterThreeReceivesAndCanBeReplayed() {
        data.subscription(customer, coffee, 2, Frequency.WEEKLY, TODAY);
        failureInjector.update(FailureMode.PERMANENT, null);
        long orderId = billing.runBillingCycle(TODAY).orders().get(0).getId();
        assertThat(messaging.queue().size()).isEqualTo(1);

        // Delivery 1 fails; the message goes back to the queue with a 2s back-off.
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertOrder(orderId, OrderStatus.PENDING, 1);
        assertThat(order(orderId).getLastError()).contains("delivery 1");
        assertThat(worker.pollOnce(Duration.ZERO)).as("still backing off").isZero();

        clock.advance(BACKOFF);
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertOrder(orderId, OrderStatus.PENDING, 2);

        // Delivery 3 is the last one maxReceiveCount allows: the order fails for good.
        clock.advance(BACKOFF.multipliedBy(2));
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertOrder(orderId, OrderStatus.FAILED, 3);

        // The next receive finds the message exhausted and the redrive policy moves it to the DLQ.
        assertThat(worker.pollOnce(Duration.ZERO)).isZero();
        assertThat(messaging.queue().size()).isZero();
        List<DeadLetter> deadLetters = operations.deadLetters();
        assertThat(deadLetters).singleElement().satisfies(deadLetter -> {
            assertThat(deadLetter.orderId()).isEqualTo(orderId);
            assertThat(deadLetter.payload()).contains("\"orderId\":" + orderId);
        });
        assertThat(operations.queueStats()).isEqualTo(new QueueStats("in-memory", 0, 0, 1, 3));

        // Fix the "outage", replay: the order is PENDING again and the message is back on the queue.
        failureInjector.update(FailureMode.NONE, null);
        ReplayResult replay = operations.replay(null);
        assertThat(replay.replayed()).isEqualTo(1);
        assertThat(replay.messageIds()).containsExactly(deadLetters.get(0).messageId());
        assertOrder(orderId, OrderStatus.PENDING, 3);
        assertThat(messaging.deadLetterQueue().size()).isZero();

        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);
        assertOrder(orderId, OrderStatus.FULFILLED, 4);
        assertThat(order(orderId).getLastError()).isNull();
        assertThat(operations.queueStats()).isEqualTo(new QueueStats("in-memory", 0, 0, 0, 3));
    }

    @Test
    void replayingSelectedDeadLettersLeavesTheOthersInTheQueue() {
        data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        data.subscription(customer, tea, 1, Frequency.WEEKLY, TODAY);
        failureInjector.update(FailureMode.PERMANENT, null);
        List<Order> billed = billing.runBillingCycle(TODAY).orders();
        exhaustRetries();
        assertThat(operations.deadLetters()).hasSize(2);

        failureInjector.update(FailureMode.NONE, null);
        long coffeeOrder = billed.get(0).getSubscription().getPlan().getCode().equals("COFFEE")
                ? billed.get(0).getId() : billed.get(1).getId();
        String coffeeMessage = operations.deadLetters().stream()
                .filter(deadLetter -> deadLetter.orderId() == coffeeOrder)
                .findFirst().orElseThrow().messageId();

        assertThat(operations.replay(List.of(coffeeMessage)).replayed()).isEqualTo(1);
        worker.pollOnce(Duration.ZERO);

        assertOrder(coffeeOrder, OrderStatus.FULFILLED, 4);
        assertThat(operations.deadLetters()).singleElement()
                .satisfies(deadLetter -> assertThat(deadLetter.orderId()).isNotEqualTo(coffeeOrder));
    }

    @Test
    void transientFailuresAreRetriedWithoutReachingTheDeadLetterQueue() {
        data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        failureInjector.update(FailureMode.TRANSIENT, null);
        long orderId = billing.runBillingCycle(TODAY).orders().get(0).getId();

        worker.pollOnce(Duration.ZERO);
        assertOrder(orderId, OrderStatus.PENDING, 1);
        clock.advance(BACKOFF);
        worker.pollOnce(Duration.ZERO);

        assertOrder(orderId, OrderStatus.FULFILLED, 2);
        assertThat(messaging.queue().size()).isZero();
        assertThat(messaging.deadLetterQueue().size()).isZero();
    }

    @Test
    void aDuplicateDeliveryOfAFulfilledOrderIsDroppedWithoutReprocessing() {
        data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        long orderId = billing.runBillingCycle(TODAY).orders().get(0).getId();
        worker.pollOnce(Duration.ZERO);
        assertOrder(orderId, OrderStatus.FULFILLED, 1);

        // At-least-once delivery: the same event arrives again (e.g. republished by the outbox relay).
        String payload = outbox.findByAggregateTypeAndAggregateId("Order", orderId).get(0).getPayload();
        messaging.topic().publish(new OutboundMessage(payload, Map.of()));
        assertThat(worker.pollOnce(Duration.ZERO)).isEqualTo(1);

        assertOrder(orderId, OrderStatus.FULFILLED, 1);
        assertThat(messaging.queue().size()).isZero();
    }

    /** Polls until every queued message has been received maxReceiveCount times and dead-lettered. */
    private void exhaustRetries() {
        for (int round = 0; round < 4; round++) {
            worker.pollOnce(Duration.ZERO);
            clock.advance(Duration.ofMinutes(1));
        }
        assertThat(messaging.queue().size()).isZero();
    }

    private Order order(long orderId) {
        return orders.findById(orderId).orElseThrow();
    }

    private void assertOrder(long orderId, OrderStatus status, int attempts) {
        Order order = order(orderId);
        assertThat(order.getStatus()).isEqualTo(status);
        assertThat(order.getAttempts()).isEqualTo(attempts);
    }
}
