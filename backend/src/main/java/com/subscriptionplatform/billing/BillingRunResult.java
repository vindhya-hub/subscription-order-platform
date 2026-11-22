package com.subscriptionplatform.billing;

import com.subscriptionplatform.order.Order;
import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of one billing run.
 *
 * @param dueSubscriptions active subscriptions whose next run date was on or before {@code asOf}
 * @param orders           orders created by this run (empty when the run had already happened)
 */
public record BillingRunResult(LocalDate asOf, int dueSubscriptions, List<Order> orders) {

    public int ordersCreated() {
        return orders.size();
    }
}
