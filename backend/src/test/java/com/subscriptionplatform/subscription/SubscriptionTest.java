package com.subscriptionplatform.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.common.BusinessRuleException;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.support.Entities;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Schedule and status transitions of a subscription. */
class SubscriptionTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
    private static final Instant NOW = Instant.parse("2026-03-02T08:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(3600);

    private final Customer customer = Entities.customer(1, "Ava Patel");
    private final Plan coffee = Entities.plan(1, "COFFEE", "16.50");

    @Test
    void startsActiveAndIsDueFromItsFirstRunDate() {
        Subscription subscription = weekly();

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getNextRunDate()).isEqualTo(MONDAY);
        assertThat(subscription.isDue(MONDAY.minusDays(1))).isFalse();
        assertThat(subscription.isDue(MONDAY)).isTrue();
        assertThat(subscription.isDue(MONDAY.plusDays(10))).isTrue();
    }

    @Test
    void advancingTheScheduleMovesToTheNextCycle() {
        Subscription subscription = weekly();

        assertThat(subscription.advanceSchedule(LATER)).isEqualTo(MONDAY.plusWeeks(1));
        assertThat(subscription.getCycleIndex()).isEqualTo(1);
        assertThat(subscription.getUpdatedAt()).isEqualTo(LATER);
        assertThat(subscription.isDue(MONDAY)).isFalse();
    }

    @Test
    void recurringAmountIsUnitPriceTimesQuantity() {
        Subscription subscription = Subscription.start(customer, coffee, 3, Frequency.WEEKLY, MONDAY, NOW);

        assertThat(subscription.getRecurringAmount()).isEqualByComparingTo("49.50");
    }

    @Test
    void quantityMustBeBetweenOneAndOneHundred() {
        assertThatThrownBy(() -> Subscription.start(customer, coffee, 0, Frequency.WEEKLY, MONDAY, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Subscription.start(customer, coffee, 101, Frequency.WEEKLY, MONDAY, NOW))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pausedSubscriptionsAreNotDueAndPausingTwiceIsANoOp() {
        Subscription subscription = weekly();

        assertThat(subscription.pause(LATER)).isTrue();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.PAUSED);
        assertThat(subscription.isDue(MONDAY)).isFalse();
        assertThat(subscription.pause(LATER)).isFalse();
    }

    @Test
    void resumingSkipsCyclesThatFellDueWhilePaused() {
        Subscription subscription = weekly();
        subscription.pause(NOW);

        // Paused through the runs of 2 Mar, 9 Mar and 16 Mar; resumed on Wednesday 18 Mar.
        assertThat(subscription.resume(LocalDate.of(2026, 3, 18), LATER)).isTrue();

        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(subscription.getNextRunDate()).isEqualTo(LocalDate.of(2026, 3, 23));
    }

    @Test
    void resumingKeepsARunDateThatIsStillAhead() {
        Subscription subscription = weekly();
        subscription.pause(NOW);

        subscription.resume(MONDAY.minusDays(3), LATER);

        assertThat(subscription.getNextRunDate()).isEqualTo(MONDAY);
    }

    @Test
    void resumingAnActiveSubscriptionIsANoOp() {
        Subscription subscription = weekly();

        assertThat(subscription.resume(MONDAY.plusDays(30), LATER)).isFalse();
        assertThat(subscription.getNextRunDate()).isEqualTo(MONDAY);
    }

    @Test
    void cancellationIsTerminal() {
        Subscription subscription = weekly();

        assertThat(subscription.cancel(LATER)).isTrue();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(subscription.getCancelledAt()).isEqualTo(LATER);
        assertThat(subscription.isDue(MONDAY)).isFalse();

        assertThat(subscription.cancel(LATER.plusSeconds(1))).isFalse();
        assertThat(subscription.getCancelledAt()).isEqualTo(LATER);
        assertThatThrownBy(() -> subscription.pause(LATER)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> subscription.resume(MONDAY, LATER)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void pausedSubscriptionsCanBeCancelled() {
        Subscription subscription = weekly();
        subscription.pause(NOW);

        assertThat(subscription.cancel(LATER)).isTrue();
        assertThat(subscription.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
    }

    private Subscription weekly() {
        return Subscription.start(customer, coffee, 1, Frequency.WEEKLY, MONDAY, NOW);
    }
}
