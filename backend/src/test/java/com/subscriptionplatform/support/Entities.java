package com.subscriptionplatform.support;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.subscription.Frequency;
import com.subscriptionplatform.subscription.Subscription;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.test.util.ReflectionTestUtils;

/** Entities for plain unit tests, with the ids JPA would normally assign. */
public final class Entities {

    public static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");

    private Entities() {
    }

    public static Customer customer(long id, String name) {
        return withId(new Customer(name, name.replace(' ', '.') + "@example.com", CREATED_AT), id);
    }

    public static Plan plan(long id, String code, String unitPrice) {
        return withId(new Plan(code, "Plan " + code, null, new BigDecimal(unitPrice), "USD", CREATED_AT), id);
    }

    public static Subscription subscription(long id, Frequency frequency, LocalDate firstRunDate) {
        return subscription(id, customer(id, "Customer " + id), plan(id, "PLAN-" + id, "10.00"), 1, frequency,
                firstRunDate);
    }

    public static Subscription subscription(long id, Customer customer, Plan plan, int quantity, Frequency frequency,
                                            LocalDate firstRunDate) {
        return withId(Subscription.start(customer, plan, quantity, frequency, firstRunDate, CREATED_AT), id);
    }

    public static Order order(long id, Subscription subscription, LocalDate runDate) {
        return withId(Order.forCycle(subscription, runDate, CREATED_AT), id);
    }

    public static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
