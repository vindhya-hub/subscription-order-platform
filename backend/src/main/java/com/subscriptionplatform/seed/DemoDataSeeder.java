package com.subscriptionplatform.seed;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.catalog.PlanRepository;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.customer.CustomerRepository;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import com.subscriptionplatform.subscription.SubscriptionStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds plans, customers and subscriptions with dates relative to today, so a fresh instance has history
 * to look at and something due right away. Runs only against an empty database.
 *
 * <p>After seeding, a billing run for today bills three subscriptions (two due today, one that missed
 * yesterday's run); a run for tomorrow bills one more. One subscription is paused with a missed cycle
 * (resuming it skips that cycle) and one is cancelled.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final OrderRepository orders;
    private final Clock clock;

    public DemoDataSeeder(PlanRepository plans, CustomerRepository customers, SubscriptionRepository subscriptions,
                          OrderRepository orders, Clock clock) {
        this.plans = plans;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.orders = orders;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (plans.count() > 0) {
            log.info("Database already has data; skipping demo seed");
            return;
        }
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.now(clock);

        Plan coffee = plans.save(new Plan("COFFEE-BEANS", "Single-Origin Coffee Beans",
                "12 oz bag, roasted to order", new BigDecimal("16.50"), "USD", now));
        Plan tea = plans.save(new Plan("TEA-SAMPLER", "Loose Leaf Tea Sampler",
                "Six seasonal teas", new BigDecimal("22.00"), "USD", now));
        Plan mealKit = plans.save(new Plan("MEAL-KIT", "Weeknight Meal Kit",
                "Three dinners for two", new BigDecimal("64.00"), "USD", now));
        Plan dogFood = plans.save(new Plan("DOG-FOOD", "Grain-Free Dog Food",
                "12 lb bag", new BigDecimal("48.99"), "USD", now));
        Plan vitamins = plans.save(new Plan("VITAMINS", "Daily Multivitamin",
                "30-day supply", new BigDecimal("18.75"), "USD", now));

        Customer ava = customers.save(new Customer("Ava Patel", "ava.patel@example.com", now));
        Customer liam = customers.save(new Customer("Liam Chen", "liam.chen@example.com", now));
        Customer sofia = customers.save(new Customer("Sofia Garcia", "sofia.garcia@example.com", now));
        Customer noah = customers.save(new Customer("Noah Williams", "noah.williams@example.com", now));
        Customer mia = customers.save(new Customer("Mia Johnson", "mia.johnson@example.com", now));

        // Due today.
        seed(ava, coffee, 2, Frequency.WEEKLY, today.minusWeeks(2), today, SubscriptionStatus.ACTIVE, now);
        seed(sofia, dogFood, 1, Frequency.MONTHLY, today.minusMonths(1), today, SubscriptionStatus.ACTIVE, now);
        // Missed yesterday's run, so it is overdue.
        seed(noah, tea, 1, Frequency.BIWEEKLY, today.minusDays(15), today.minusDays(1), SubscriptionStatus.ACTIVE, now);
        // Due tomorrow / later.
        seed(liam, mealKit, 1, Frequency.WEEKLY, today.minusDays(6), today, SubscriptionStatus.ACTIVE, now);
        seed(ava, vitamins, 1, Frequency.MONTHLY, today.plusDays(3), today, SubscriptionStatus.ACTIVE, now);
        // Paused with a cycle that fell due while paused.
        seed(mia, vitamins, 3, Frequency.MONTHLY, today.minusDays(45), today.minusDays(20), SubscriptionStatus.PAUSED, now);
        // Cancelled.
        seed(liam, coffee, 1, Frequency.BIWEEKLY, today.minusWeeks(4), today, SubscriptionStatus.CANCELLED, now);

        log.info("Seeded demo data: {} plans, {} customers, {} subscriptions, {} historical orders",
                plans.count(), customers.count(), subscriptions.count(), orders.count());
    }

    /**
     * Creates a subscription anchored on {@code anchor} and records FULFILLED orders for each cycle before
     * {@code billedBefore}, as if earlier billing runs had processed them.
     */
    private void seed(Customer customer, Plan plan, int quantity, Frequency frequency, LocalDate anchor,
                      LocalDate billedBefore, SubscriptionStatus status, Instant now) {
        Instant createdAt = earliest(anchor.minusDays(1).atTime(15, 0).toInstant(ZoneOffset.UTC), now);
        Subscription subscription = subscriptions.save(
                Subscription.start(customer, plan, quantity, frequency, anchor, createdAt));

        while (subscription.getNextRunDate().isBefore(billedBefore)) {
            Instant billedAt = subscription.getNextRunDate().atTime(6, 0).toInstant(ZoneOffset.UTC);
            Order order = Order.forCycle(subscription, subscription.getNextRunDate(), billedAt);
            order.startAttempt(billedAt.plusSeconds(2));
            order.markFulfilled(billedAt.plusSeconds(3));
            orders.save(order);
            subscription.advanceSchedule(billedAt);
        }

        switch (status) {
            case PAUSED -> subscription.pause(now);
            case CANCELLED -> subscription.cancel(now);
            case ACTIVE -> {
                // already active
            }
        }
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
