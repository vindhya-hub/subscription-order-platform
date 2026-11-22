package com.subscriptionplatform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.subscriptionplatform.events.OrderCreatedEvent;
import com.subscriptionplatform.events.OrderEventCodec;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.order.OrderStatus;
import com.subscriptionplatform.outbox.OutboxEvent;
import com.subscriptionplatform.outbox.OutboxEventRepository;
import com.subscriptionplatform.outbox.OutboxRelay;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import com.subscriptionplatform.subscription.SubscriptionStatus;
import com.subscriptionplatform.support.Entities;
import com.subscriptionplatform.support.Json;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/** Scheduling and idempotency rules of the billing run, with the persistence layer mocked out. */
@ExtendWith(MockitoExtension.class)
class BillingServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-02T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);

    @Mock
    private SubscriptionRepository subscriptions;
    @Mock
    private OrderRepository orders;
    @Mock
    private OutboxEventRepository outbox;
    @Mock
    private OutboxRelay outboxRelay;
    @Mock
    private PlatformTransactionManager transactionManager;

    private final OrderEventCodec codec = Json.codec();
    private final AtomicLong orderIds = new AtomicLong(100);
    private final List<Long> dueIds = new ArrayList<>();

    @BeforeEach
    void assignIdsOnSave() {
        lenient().doAnswer(invocation -> Entities.withId(invocation.<Order>getArgument(0), orderIds.incrementAndGet()))
                .when(orders).save(any(Order.class));
        lenient().doAnswer(invocation -> invocation.getArgument(0)).when(outbox).save(any(OutboxEvent.class));
        lenient().doAnswer(invocation -> List.copyOf(dueIds))
                .when(subscriptions).findDueIds(eq(SubscriptionStatus.ACTIVE), any(LocalDate.class));
    }

    @Test
    void billsADueSubscriptionAndAdvancesItsNextRunDate() {
        Subscription subscription = due(1, Frequency.WEEKLY, TODAY);

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.asOf()).isEqualTo(TODAY);
        assertThat(result.dueSubscriptions()).isEqualTo(1);
        assertThat(result.ordersCreated()).isEqualTo(1);
        Order order = result.orders().get(0);
        assertThat(order.getRunDate()).isEqualTo(TODAY);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getAmount()).isEqualByComparingTo("10.00");
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.plusWeeks(1));
        verify(outboxRelay).publishPending();
    }

    @Test
    void recordsAnOrderCreatedEventInTheOutboxForEveryOrder() {
        due(1, Frequency.MONTHLY, TODAY);

        Order order = billing(12).runBillingCycle(TODAY).orders().get(0);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(OrderCreatedEvent.TYPE);
        assertThat(event.getAggregateId()).isEqualTo(order.getId());
        OrderCreatedEvent payload = codec.decode(event.getPayload());
        assertThat(payload.orderId()).isEqualTo(order.getId());
        assertThat(payload.subscriptionId()).isEqualTo(1L);
        assertThat(payload.runDate()).isEqualTo(TODAY);
        assertThat(payload.amount()).isEqualByComparingTo("10.00");
    }

    @Test
    void catchesUpOnEveryMissedCycle() {
        Subscription subscription = due(1, Frequency.WEEKLY, TODAY.minusWeeks(3));

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.orders()).extracting(Order::getRunDate).containsExactly(
                TODAY.minusWeeks(3), TODAY.minusWeeks(2), TODAY.minusWeeks(1), TODAY);
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.plusWeeks(1));
    }

    @Test
    void catchUpIsCappedPerRun() {
        Subscription subscription = due(1, Frequency.WEEKLY, TODAY.minusWeeks(3));

        BillingRunResult result = billing(2).runBillingCycle(TODAY);

        assertThat(result.ordersCreated()).isEqualTo(2);
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.minusWeeks(1));
    }

    @Test
    void neverCreatesASecondOrderForTheSameSubscriptionAndRunDate() {
        Subscription subscription = due(1, Frequency.WEEKLY, TODAY);
        lenient().doReturn(true).when(orders).existsBySubscriptionIdAndRunDate(1L, TODAY);

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.ordersCreated()).isZero();
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.plusWeeks(1));
        verify(orders, never()).save(any());
        verify(outboxRelay, never()).publishPending();
    }

    @Test
    void repeatingARunForTheSameDateCreatesNothing() {
        Subscription subscription = due(1, Frequency.WEEKLY, TODAY);
        BillingService billing = billing(12);
        billing.runBillingCycle(TODAY);

        // Even if a (stale) due query still returns the subscription, it is no longer due for TODAY.
        BillingRunResult second = billing.runBillingCycle(TODAY);

        assertThat(second.dueSubscriptions()).isEqualTo(1);
        assertThat(second.ordersCreated()).isZero();
        assertThat(subscription.getNextRunDate()).isEqualTo(TODAY.plusWeeks(1));
        verify(orders, times(1)).save(any());
    }

    @Test
    void subscriptionsPausedOrCancelledAfterTheDueQueryAreNotBilled() {
        Subscription paused = due(1, Frequency.WEEKLY, TODAY);
        Subscription cancelled = due(2, Frequency.WEEKLY, TODAY);
        paused.pause(NOW);
        cancelled.cancel(NOW);

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.ordersCreated()).isZero();
        assertThat(paused.getNextRunDate()).isEqualTo(TODAY);
        assertThat(cancelled.getNextRunDate()).isEqualTo(TODAY);
        verify(orders, never()).save(any());
    }

    @Test
    void aConcurrentlyBilledSubscriptionIsSkippedWithoutFailingTheRun() {
        due(1, Frequency.WEEKLY, TODAY);
        due(2, Frequency.WEEKLY, TODAY);
        // Another run inserted the order for subscription 1 first, so the unique constraint rejects ours.
        doAnswer(invocation -> {
            Order order = invocation.getArgument(0);
            if (order.getSubscription().getId() == 1L) {
                throw new DataIntegrityViolationException("uk_orders_subscription_run_date");
            }
            return Entities.withId(order, orderIds.incrementAndGet());
        }).when(orders).save(any(Order.class));

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.dueSubscriptions()).isEqualTo(2);
        assertThat(result.orders()).extracting(order -> order.getSubscription().getId()).containsExactly(2L);
    }

    @Test
    void aFailedImmediatePublishDoesNotFailTheRun() {
        due(1, Frequency.WEEKLY, TODAY);
        doThrow(new IllegalStateException("broker down")).when(outboxRelay).publishPending();

        BillingRunResult result = billing(12).runBillingCycle(TODAY);

        assertThat(result.ordersCreated()).isEqualTo(1);
    }

    @Test
    void defaultsToTodayAndDoesNothingWhenNothingIsDue() {
        BillingRunResult result = billing(12).runBillingCycle(null);

        assertThat(result.asOf()).isEqualTo(TODAY);
        assertThat(result.dueSubscriptions()).isZero();
        assertThat(result.orders()).isEmpty();
        verify(subscriptions).findDueIds(SubscriptionStatus.ACTIVE, TODAY);
        verify(outboxRelay, never()).publishPending();
    }

    private BillingService billing(int maxCatchUpCycles) {
        BillingProperties properties = new BillingProperties(maxCatchUpCycles,
                new BillingProperties.Scheduler(true, "0 0 6 * * *", "UTC"));
        return new BillingService(subscriptions, orders, outbox, outboxRelay, codec, properties, transactionManager,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** An active subscription that the due query returns, in registration order. */
    private Subscription due(long id, Frequency frequency, LocalDate firstRunDate) {
        Subscription subscription = Entities.subscription(id, frequency, firstRunDate);
        dueIds.add(id);
        lenient().doReturn(Optional.of(subscription)).when(subscriptions).findWithDetailsById(id);
        return subscription;
    }
}
