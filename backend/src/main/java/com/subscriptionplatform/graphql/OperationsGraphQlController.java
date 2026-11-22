package com.subscriptionplatform.graphql;

import com.subscriptionplatform.catalog.CatalogService;
import com.subscriptionplatform.operations.DeadLetter;
import com.subscriptionplatform.operations.OperationsService;
import com.subscriptionplatform.operations.QueueStats;
import com.subscriptionplatform.operations.ReplayResult;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderService;
import com.subscriptionplatform.processing.FailureInjectionSettings;
import com.subscriptionplatform.processing.FailureInjector;
import com.subscriptionplatform.processing.FailureMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;

/** Pipeline operations: dead letters, replay, queue statistics and failure injection. */
@Controller
public class OperationsGraphQlController {

    private final OperationsService operations;
    private final OrderService orders;
    private final FailureInjector failureInjector;
    private final CatalogService catalog;

    public OperationsGraphQlController(OperationsService operations, OrderService orders,
                                       FailureInjector failureInjector, CatalogService catalog) {
        this.operations = operations;
        this.orders = orders;
        this.failureInjector = failureInjector;
        this.catalog = catalog;
    }

    @QueryMapping
    public List<DeadLetter> deadLetters() {
        return operations.deadLetters();
    }

    @QueryMapping
    public QueueStats queueStats() {
        return operations.queueStats();
    }

    @QueryMapping
    public FailureInjectionSettings failureInjection() {
        return failureInjector.current();
    }

    @MutationMapping
    public ReplayResult replayDeadLetters(@Argument List<String> messageIds) {
        return operations.replay(messageIds);
    }

    @MutationMapping
    public FailureInjectionSettings setFailureInjection(@Argument FailureMode mode, @Argument String planCode) {
        if (StringUtils.hasText(planCode)) {
            catalog.requirePlan(planCode.strip());
        }
        return failureInjector.update(mode, planCode);
    }

    /** {@code DeadLetter.order}, loaded for every dead letter in the response with a single query. */
    @BatchMapping(typeName = "DeadLetter", field = "order")
    public Map<DeadLetter, Order> order(List<DeadLetter> deadLetters) {
        List<Long> ids = deadLetters.stream().map(DeadLetter::orderId).filter(Objects::nonNull).distinct().toList();
        Map<Long, Order> byId = orders.findAllById(ids).stream()
                .collect(Collectors.toMap(Order::getId, Function.identity()));
        Map<DeadLetter, Order> result = new LinkedHashMap<>();
        deadLetters.forEach(deadLetter -> result.put(deadLetter,
                deadLetter.orderId() == null ? null : byId.get(deadLetter.orderId())));
        return result;
    }
}
