package com.subscriptionplatform.billing;

import com.subscriptionplatform.events.OrderCreatedEvent;
import com.subscriptionplatform.events.OrderEventCodec;
import com.subscriptionplatform.order.Order;
import com.subscriptionplatform.order.OrderRepository;
import com.subscriptionplatform.outbox.OutboxEvent;
import com.subscriptionplatform.outbox.OutboxEventRepository;
import com.subscriptionplatform.outbox.OutboxRelay;
import com.subscriptionplatform.subscription.Subscription;
import com.subscriptionplatform.subscription.SubscriptionRepository;
import com.subscriptionplatform.subscription.SubscriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns due subscription cycles into orders.
 *
 * <p>Each subscription is billed in its own transaction: the order rows, their outbox events and the
 * advanced schedule commit together, so a failure on one subscription never affects another. The run is
 * idempotent per subscription and run date:
 * <ul>
 *   <li>after a run for {@code asOf}, every billed subscription's next run date is after {@code asOf}, so
 *       repeating the run finds nothing to do;</li>
 *   <li>an existing order for the same (subscription, run date) is never duplicated, and a unique constraint
 *       plus optimistic locking make a concurrent run (scheduler and manual trigger, or two instances) back
 *       off instead of double-billing.</li>
 * </ul>
 */
@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final SubscriptionRepository subscriptions;
    private final OrderRepository orders;
    private final OutboxEventRepository outbox;
    private final OutboxRelay outboxRelay;
    private final OrderEventCodec codec;
    private final BillingProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public BillingService(SubscriptionRepository subscriptions, OrderRepository orders, OutboxEventRepository outbox,
                          OutboxRelay outboxRelay, OrderEventCodec codec, BillingProperties properties,
                          PlatformTransactionManager transactionManager, Clock clock) {
        this.subscriptions = subscriptions;
        this.orders = orders;
        this.outbox = outbox;
        this.outboxRelay = outboxRelay;
        this.codec = codec;
        this.properties = properties;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Bills every active subscription that is due on or before {@code asOf} (defaults to today). */
    public BillingRunResult runBillingCycle(LocalDate asOf) {
        LocalDate runDate = asOf != null ? asOf : LocalDate.now(clock);
        List<Long> dueIds = subscriptions.findDueIds(SubscriptionStatus.ACTIVE, runDate);
        List<Order> created = new ArrayList<>();
        for (Long subscriptionId : dueIds) {
            try {
                List<Order> billed = transactionTemplate.execute(status -> billSubscription(subscriptionId, runDate));
                if (billed != null) {
                    created.addAll(billed);
                }
            } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
                // Another run billed (or modified) this subscription concurrently; its result stands.
                log.info("Skipped subscription {}: changed concurrently ({})", subscriptionId, e.getClass().getSimpleName());
            }
        }
        log.info("Billing run as of {}: {} due subscription(s), {} order(s) created", runDate, dueIds.size(), created.size());

        if (!created.isEmpty()) {
            try {
                outboxRelay.publishPending();
            } catch (RuntimeException e) {
                // Orders and events are committed; the scheduled relay sweep will publish them.
                log.warn("Immediate publish after billing failed; relying on the outbox sweep: {}", e.toString());
            }
        }
        return new BillingRunResult(runDate, dueIds.size(), List.copyOf(created));
    }

    private List<Order> billSubscription(Long subscriptionId, LocalDate asOf) {
        Subscription subscription = subscriptions.findWithDetailsById(subscriptionId).orElse(null);
        if (subscription == null || !subscription.isDue(asOf)) {
            return List.of(); // cancelled, paused or billed since the due query ran
        }
        Instant now = Instant.now(clock);
        List<Order> created = new ArrayList<>();
        int cycles = 0;
        while (subscription.isDue(asOf) && cycles < properties.maxCatchUpCycles()) {
            LocalDate cycleDate = subscription.getNextRunDate();
            if (orders.existsBySubscriptionIdAndRunDate(subscriptionId, cycleDate)) {
                log.debug("Subscription {} already has an order for {}; not creating another", subscriptionId, cycleDate);
            } else {
                Order order = orders.save(Order.forCycle(subscription, cycleDate, now));
                outbox.save(new OutboxEvent("Order", order.getId(), OrderCreatedEvent.TYPE,
                        codec.encode(OrderCreatedEvent.from(order, now)), now));
                created.add(order);
            }
            subscription.advanceSchedule(now);
            cycles++;
        }
        if (subscription.isDue(asOf)) {
            log.warn("Subscription {} is still behind after {} cycles; the next run continues from {}",
                    subscriptionId, cycles, subscription.getNextRunDate());
        }
        return created;
    }
}
