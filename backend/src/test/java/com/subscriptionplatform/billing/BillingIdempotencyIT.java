package com.subscriptionplatform.billing;

import static com.subscriptionplatform.support.TestClockConfiguration.TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.messaging.inmemory.InMemoryMessaging;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.outbox.OutboxEventRepository;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import com.subscriptionplatform.subscription.SubscriptionService;
import com.subscriptionplatform.support.ManualPipelineTest;
import com.subscriptionplatform.support.MutableClock;
import com.subscriptionplatform.support.TestClockConfiguration;
import com.subscriptionplatform.support.TestData;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/** Billing against the real database: idempotency per (subscription, run date), also under concurrency. */
@ManualPipelineTest
class BillingIdempotencyIT {

    @Autowired
    private BillingService billing;
    @Autowired
    private SubscriptionService subscriptionService;
    @Autowired
    private SubscriptionRepository subscriptions;
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

    @BeforeEach
    void setUp() {
        clock.set(TestClockConfiguration.START);
        data.reset();
        customer = data.customer("Ava Patel");
        coffee = data.plan("COFFEE", "16.50");
    }

    @Test
    void runningTheSameCycleTwiceBillsOnce() {
        Subscription subscription = data.subscription(customer, coffee, 2, Frequency.WEEKLY, TODAY);

        BillingRunResult first = billing.runBillingCycle(TODAY);
        BillingRunResult second = billing.runBillingCycle(TODAY);

        assertThat(first.ordersCreated()).isEqualTo(1);
        assertThat(second.dueSubscriptions()).isZero();
        assertThat(second.ordersCreated()).isZero();
        assertThat(orders.findBySubscriptionIdOrderByRunDateAsc(subscription.getId()))
                .extracting(Order::getRunDate, order -> order.getAmount().toPlainString())
                .containsExactly(tuple(TODAY, "33.00"));
        assertThat(nextRunDate(subscription)).isEqualTo(TODAY.plusWeeks(1));
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        assertThat(messaging.queue().size()).isEqualTo(1);
    }

    @Test
    void concurrentRunsBillEachSubscriptionAndRunDateExactlyOnce() throws Exception {
        List<Subscription> due = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            due.add(data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY));
        }
        int runs = 4;
        ExecutorService executor = Executors.newFixedThreadPool(runs);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<BillingRunResult>> results = new ArrayList<>();
        try {
            for (int i = 0; i < runs; i++) {
                Callable<BillingRunResult> run = () -> {
                    start.await();
                    return billing.runBillingCycle(TODAY);
                };
                results.add(executor.submit(run));
            }
            start.countDown();
            int created = 0;
            for (Future<BillingRunResult> result : results) {
                created += result.get(30, TimeUnit.SECONDS).ordersCreated();
            }
            assertThat(created).isEqualTo(6);
        } finally {
            executor.shutdownNow();
        }

        assertThat(orders.count()).isEqualTo(6);
        for (Subscription subscription : due) {
            assertThat(orders.findBySubscriptionIdOrderByRunDateAsc(subscription.getId()))
                    .extracting(Order::getRunDate).containsExactly(TODAY);
            assertThat(nextRunDate(subscription)).isEqualTo(TODAY.plusWeeks(1));
        }
        assertThat(messaging.queue().size()).as("one OrderCreated event per order").isEqualTo(6);
    }

    @Test
    void theDatabaseRejectsASecondOrderForTheSameSubscriptionAndRunDate() {
        Subscription subscription = data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        orders.saveAndFlush(Order.forCycle(subscription, TODAY, clock.instant()));

        assertThatThrownBy(() -> orders.saveAndFlush(Order.forCycle(subscription, TODAY, clock.instant())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cyclesMissedWhilePausedAreNeverBilled() {
        Subscription subscription = data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        billing.runBillingCycle(TODAY);
        subscriptionService.pause(subscription.getId());

        clock.advance(Duration.ofDays(15));
        LocalDate resumedOn = TODAY.plusDays(15);
        assertThat(subscriptionService.resume(subscription.getId()).getNextRunDate()).isEqualTo(TODAY.plusWeeks(3));
        assertThat(billing.runBillingCycle(resumedOn).ordersCreated()).isZero();
        assertThat(billing.runBillingCycle(TODAY.plusWeeks(3)).ordersCreated()).isEqualTo(1);

        assertThat(orders.findBySubscriptionIdOrderByRunDateAsc(subscription.getId()))
                .extracting(Order::getRunDate).containsExactly(TODAY, TODAY.plusWeeks(3));
    }

    @Test
    void pausedAndCancelledSubscriptionsAreNotBilled() {
        Subscription active = data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        Subscription paused = data.subscription(customer, coffee, 1, Frequency.WEEKLY, TODAY);
        Subscription cancelled = data.subscription(customer, coffee, 1, Frequency.MONTHLY, TODAY);
        subscriptionService.pause(paused.getId());
        subscriptionService.cancel(cancelled.getId());

        BillingRunResult result = billing.runBillingCycle(TODAY);

        assertThat(result.dueSubscriptions()).isEqualTo(1);
        assertThat(result.orders()).extracting(order -> order.getSubscription().getId()).containsExactly(active.getId());
    }

    @Test
    void aRunForALaterDateCatchesUpOnEveryCycleUpToThatDate() {
        Subscription subscription = data.subscription(customer, coffee, 1, Frequency.BIWEEKLY, TODAY);

        BillingRunResult result = billing.runBillingCycle(TODAY.plusWeeks(4));

        assertThat(result.orders()).extracting(Order::getRunDate)
                .containsExactly(TODAY, TODAY.plusWeeks(2), TODAY.plusWeeks(4));
        assertThat(nextRunDate(subscription)).isEqualTo(TODAY.plusWeeks(6));
    }

    private LocalDate nextRunDate(Subscription subscription) {
        return subscriptions.findById(subscription.getId()).orElseThrow().getNextRunDate();
    }
}
