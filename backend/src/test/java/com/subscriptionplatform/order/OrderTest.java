package com.subscriptionplatform.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.support.Entities;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Order status transitions driven by the processing pipeline. */
class OrderTest {

    private static final LocalDate RUN_DATE = LocalDate.of(2026, 3, 2);
    private static final Instant NOW = Instant.parse("2026-03-02T06:00:00Z");

    private final Subscription subscription = Entities.subscription(7, Entities.customer(1, "Ava Patel"),
            Entities.plan(1, "COFFEE", "16.50"), 3, Frequency.WEEKLY, RUN_DATE);

    @Test
    void newOrderSnapshotsPriceAndQuantityAndStartsPending() {
        Order order = Order.forCycle(subscription, RUN_DATE, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getRunDate()).isEqualTo(RUN_DATE);
        assertThat(order.getQuantity()).isEqualTo(3);
        assertThat(order.getUnitPrice()).isEqualByComparingTo("16.50");
        assertThat(order.getAmount()).isEqualByComparingTo("49.50");
        assertThat(order.getCurrency()).isEqualTo("USD");
        assertThat(order.getAttempts()).isZero();
        assertThat(order.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void failedAttemptsAreCountedAndTheLastErrorIsKept() {
        Order order = Order.forCycle(subscription, RUN_DATE, NOW);

        order.startAttempt(NOW.plusSeconds(1));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PROCESSING);
        assertThat(order.getAttempts()).isEqualTo(1);

        order.markRetryPending("gateway timeout", NOW.plusSeconds(2));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getLastError()).isEqualTo("gateway timeout");

        order.startAttempt(NOW.plusSeconds(3));
        order.markFailed("gateway outage", NOW.plusSeconds(4));
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(order.getAttempts()).isEqualTo(2);
        assertThat(order.getLastError()).isEqualTo("gateway outage");
    }

    @Test
    void onlyFailedOrdersCanBeRequeued() {
        Order order = Order.forCycle(subscription, RUN_DATE, NOW);
        assertThat(order.requeue(NOW)).isFalse();

        order.startAttempt(NOW);
        order.markFailed("gateway outage", NOW);

        assertThat(order.requeue(NOW.plusSeconds(60))).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.requeue(NOW.plusSeconds(61))).isFalse();
    }

    @Test
    void fulfilmentClearsTheErrorAndIsFinal() {
        Order order = Order.forCycle(subscription, RUN_DATE, NOW);
        order.startAttempt(NOW);
        order.markRetryPending("gateway timeout", NOW);
        order.startAttempt(NOW.plusSeconds(5));

        order.markFulfilled(NOW.plusSeconds(6));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.FULFILLED);
        assertThat(order.getLastError()).isNull();
        assertThat(order.getFulfilledAt()).isEqualTo(NOW.plusSeconds(6));
        assertThat(order.requeue(NOW.plusSeconds(7))).isFalse();
        assertThatIllegalStateException().isThrownBy(() -> order.startAttempt(NOW.plusSeconds(8)));
    }

    @Test
    void longErrorsAreTruncatedToFitTheColumn() {
        Order order = Order.forCycle(subscription, RUN_DATE, NOW);
        order.startAttempt(NOW);

        order.markFailed("x".repeat(2_000), NOW);

        assertThat(order.getLastError()).hasSize(500).endsWith("...");
    }
}
