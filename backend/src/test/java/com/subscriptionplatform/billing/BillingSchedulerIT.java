package com.subscriptionplatform.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import com.subscriptionplatform.support.TestData;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** The {@code @Scheduled} billing job, with the cron overridden to fire every second. */
@SpringBootTest(properties = {
        "app.billing.scheduler.enabled=true",
        "app.billing.scheduler.cron=* * * * * *",
        "app.messaging.consumer.enabled=false"
})
@ActiveProfiles("test")
@Import(TestData.class)
class BillingSchedulerIT {

    @Autowired
    private TestData data;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private SubscriptionRepository subscriptions;
    @Autowired
    private Clock clock;

    @Test
    void theScheduledJobBillsDueSubscriptionsOnItsOwn() {
        data.reset();
        LocalDate today = LocalDate.now(clock);
        Subscription subscription = data.subscription(data.customer("Ava Patel"), data.plan("COFFEE", "16.50"), 1,
                Frequency.MONTHLY, today);

        await().atMost(Duration.ofSeconds(15))
                .until(() -> orders.existsBySubscriptionIdAndRunDate(subscription.getId(), today));

        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getNextRunDate())
                .isEqualTo(today.plusMonths(1));
        assertThat(orders.findBySubscriptionIdOrderByRunDateAsc(subscription.getId())).hasSize(1);
    }
}
