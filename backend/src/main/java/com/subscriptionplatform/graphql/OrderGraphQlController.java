package com.subscriptionplatform.graphql;

import com.subscriptionplatform.billing.BillingRunResult;
import com.subscriptionplatform.billing.BillingService;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderService;
import com.subscriptionplatform.order.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class OrderGraphQlController {

    private static final int DEFAULT_LIMIT = 100;

    private final OrderService orders;
    private final BillingService billing;

    public OrderGraphQlController(OrderService orders, BillingService billing) {
        this.orders = orders;
        this.billing = billing;
    }

    @QueryMapping
    public List<Order> orders(@Argument OrderStatus status, @Argument Long subscriptionId, @Argument Integer limit) {
        return orders.search(status, subscriptionId, limit != null ? limit : DEFAULT_LIMIT);
    }

    @QueryMapping
    public Order order(@Argument Long id) {
        return orders.find(id).orElse(null);
    }

    @MutationMapping
    public BillingRunResult runBillingCycle(@Argument LocalDate asOf) {
        return billing.runBillingCycle(asOf);
    }
}
