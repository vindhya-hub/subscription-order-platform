package com.subscriptionplatform.order;

import com.subscriptionplatform.common.NotFoundException;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Order queries plus the status transitions driven by the processing pipeline. */
@Service
public class OrderService {

    public static final int MAX_PAGE_SIZE = 500;

    private final OrderRepository orders;
    private final Clock clock;

    public OrderService(OrderRepository orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Order> search(OrderStatus status, Long subscriptionId, int limit) {
        Specification<Order> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (subscriptionId != null) {
                predicates.add(cb.equal(root.get("subscription").get("id"), subscriptionId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        int pageSize = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        return orders.findAll(filter, PageRequest.of(0, pageSize, Sort.by(Sort.Direction.DESC, "id"))).getContent();
    }

    @Transactional(readOnly = true)
    public Optional<Order> find(Long id) {
        return orders.findWithDetailsById(id);
    }

    @Transactional(readOnly = true)
    public List<Order> findAllById(Collection<Long> ids) {
        return ids.isEmpty() ? List.of() : orders.findByIdIn(ids);
    }

    /** Orders grouped by subscription id, newest run date first. */
    @Transactional(readOnly = true)
    public Map<Long, List<Order>> findBySubscriptionIds(Collection<Long> subscriptionIds) {
        Map<Long, List<Order>> grouped = new LinkedHashMap<>();
        if (subscriptionIds.isEmpty()) {
            return grouped;
        }
        for (Order order : orders.findBySubscriptionIdInOrderByRunDateDesc(subscriptionIds)) {
            grouped.computeIfAbsent(order.getSubscription().getId(), id -> new ArrayList<>()).add(order);
        }
        return grouped;
    }

    /**
     * Marks the start of a processing attempt. Returns empty when the order is already fulfilled, which
     * happens when a message is delivered more than once (SQS is at-least-once).
     */
    @Transactional
    public Optional<OrderSnapshot> startAttempt(long orderId) {
        Order order = load(orderId);
        if (order.getStatus() == OrderStatus.FULFILLED) {
            return Optional.empty();
        }
        order.startAttempt(Instant.now(clock));
        return Optional.of(OrderSnapshot.of(order));
    }

    @Transactional
    public void markFulfilled(long orderId) {
        load(orderId).markFulfilled(Instant.now(clock));
    }

    @Transactional
    public void markRetryPending(long orderId, String error) {
        load(orderId).markRetryPending(error, Instant.now(clock));
    }

    @Transactional
    public void markFailed(long orderId, String error) {
        load(orderId).markFailed(error, Instant.now(clock));
    }

    /** FAILED -> PENDING after a dead-letter replay. No-op (false) if the order is missing or moved on. */
    @Transactional
    public boolean requeue(long orderId) {
        return orders.findById(orderId)
                .map(order -> order.requeue(Instant.now(clock)))
                .orElse(false);
    }

    private Order load(long orderId) {
        return orders.findWithDetailsById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
    }
}
