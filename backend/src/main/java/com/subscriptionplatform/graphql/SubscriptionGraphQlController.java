package com.subscriptionplatform.graphql;

import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderService;
import com.subscriptionplatform.subscription.CreateSubscriptionInput;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionService;
import com.subscriptionplatform.subscription.SubscriptionStatus;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class SubscriptionGraphQlController {

    private final SubscriptionService subscriptions;
    private final OrderService orders;

    public SubscriptionGraphQlController(SubscriptionService subscriptions, OrderService orders) {
        this.subscriptions = subscriptions;
        this.orders = orders;
    }

    @QueryMapping
    public List<Subscription> subscriptions(@Argument SubscriptionStatus status) {
        return subscriptions.list(status);
    }

    @QueryMapping
    public Subscription subscription(@Argument Long id) {
        return subscriptions.find(id).orElse(null);
    }

    @MutationMapping
    public Subscription createSubscription(@Argument @Valid CreateSubscriptionInput input) {
        return subscriptions.create(input);
    }

    @MutationMapping
    public Subscription pauseSubscription(@Argument Long id) {
        return subscriptions.pause(id);
    }

    @MutationMapping
    public Subscription resumeSubscription(@Argument Long id) {
        return subscriptions.resume(id);
    }

    @MutationMapping
    public Subscription cancelSubscription(@Argument Long id) {
        return subscriptions.cancel(id);
    }

    /** {@code Subscription.orders}, loaded for all subscriptions in the response with a single query. */
    @BatchMapping(typeName = "Subscription", field = "orders")
    public Map<Subscription, List<Order>> orders(List<Subscription> sources) {
        Map<Long, List<Order>> bySubscription =
                orders.findBySubscriptionIds(sources.stream().map(Subscription::getId).distinct().toList());
        Map<Subscription, List<Order>> result = new LinkedHashMap<>();
        sources.forEach(subscription -> result.put(subscription, bySubscription.getOrDefault(subscription.getId(), List.of())));
        return result;
    }
}
