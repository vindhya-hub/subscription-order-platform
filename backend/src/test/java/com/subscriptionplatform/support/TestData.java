package com.subscriptionplatform.support;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.catalog.PlanRepository;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.customer.CustomerRepository;
import com.subscriptionplatform.messaging.inmemory.InMemoryMessaging;
import com.subscriptionplatform.processing.FailureInjector;
import com.subscriptionplatform.processing.FailureMode;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

/** Resets state between integration tests and creates reference data. Import it with {@code @Import}. */
@TestComponent
public class TestData {

    private final JdbcTemplate jdbc;
    private final PlanRepository plans;
    private final CustomerRepository customers;
    private final SubscriptionRepository subscriptions;
    private final ObjectProvider<InMemoryMessaging> inMemoryMessaging;
    private final FailureInjector failureInjector;
    private final Clock clock;

    public TestData(JdbcTemplate jdbc, PlanRepository plans, CustomerRepository customers,
                    SubscriptionRepository subscriptions, ObjectProvider<InMemoryMessaging> inMemoryMessaging,
                    FailureInjector failureInjector, Clock clock) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.customers = customers;
        this.subscriptions = subscriptions;
        this.inMemoryMessaging = inMemoryMessaging;
        this.failureInjector = failureInjector;
        this.clock = clock;
    }

    /** Empties the in-memory queues and every table, and switches failure injection off. */
    public void reset() {
        inMemoryMessaging.ifAvailable(messaging -> {
            messaging.queue().purge();
            messaging.deadLetterQueue().purge();
        });
        failureInjector.update(FailureMode.NONE, null);
        jdbc.execute("delete from outbox_events");
        jdbc.execute("delete from orders");
        jdbc.execute("delete from subscriptions");
        jdbc.execute("delete from customers");
        jdbc.execute("delete from plans");
    }

    public Plan plan(String code, String unitPrice) {
        return plans.save(new Plan(code, "Plan " + code, null, new BigDecimal(unitPrice), "USD", clock.instant()));
    }

    public Customer customer(String name) {
        String email = name.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.com";
        return customers.save(new Customer(name, email, clock.instant()));
    }

    public Subscription subscription(Customer customer, Plan plan, int quantity, Frequency frequency,
                                     LocalDate firstRunDate) {
        return subscriptions.save(Subscription.start(customer, plan, quantity, frequency, firstRunDate, clock.instant()));
    }
}
