package com.subscriptionplatform.subscription;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.catalog.PlanRepository;
import com.subscriptionplatform.common.BusinessRuleException;
import com.subscriptionplatform.common.NotFoundException;
import com.subscriptionplatform.customer.Customer;
import com.subscriptionplatform.customer.CustomerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionService.class);

    private final SubscriptionRepository subscriptions;
    private final CustomerRepository customers;
    private final PlanRepository plans;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subscriptions, CustomerRepository customers,
                               PlanRepository plans, Clock clock) {
        this.subscriptions = subscriptions;
        this.customers = customers;
        this.plans = plans;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Subscription> list(SubscriptionStatus status) {
        return status == null
                ? subscriptions.findAllByOrderByIdDesc()
                : subscriptions.findByStatusOrderByIdDesc(status);
    }

    @Transactional(readOnly = true)
    public Optional<Subscription> find(Long id) {
        return subscriptions.findWithDetailsById(id);
    }

    @Transactional
    public Subscription create(CreateSubscriptionInput input) {
        Customer customer = resolveCustomer(input);
        Plan plan = plans.findById(input.planId())
                .orElseThrow(() -> new NotFoundException("Plan", input.planId()));
        if (!plan.isActive()) {
            throw new BusinessRuleException("Plan " + plan.getCode() + " is no longer offered");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate firstRunDate = input.startDate() != null ? input.startDate() : today;
        if (firstRunDate.isBefore(today)) {
            throw new BusinessRuleException("startDate cannot be in the past");
        }

        Subscription subscription = subscriptions.save(Subscription.start(
                customer, plan, input.quantity(), input.frequency(), firstRunDate, Instant.now(clock)));
        log.info("Created subscription {} ({} x{} {}, first run {})", subscription.getId(), plan.getCode(),
                subscription.getQuantity(), subscription.getFrequency(), firstRunDate);
        return subscription;
    }

    @Transactional
    public Subscription pause(Long id) {
        Subscription subscription = load(id);
        if (subscription.pause(Instant.now(clock))) {
            log.info("Paused subscription {}", id);
        }
        return subscription;
    }

    @Transactional
    public Subscription resume(Long id) {
        Subscription subscription = load(id);
        if (subscription.resume(LocalDate.now(clock), Instant.now(clock))) {
            log.info("Resumed subscription {}; next run {}", id, subscription.getNextRunDate());
        }
        return subscription;
    }

    @Transactional
    public Subscription cancel(Long id) {
        Subscription subscription = load(id);
        if (subscription.cancel(Instant.now(clock))) {
            log.info("Cancelled subscription {}", id);
        }
        return subscription;
    }

    private Subscription load(Long id) {
        return subscriptions.findWithDetailsById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
    }

    private Customer resolveCustomer(CreateSubscriptionInput input) {
        boolean existing = input.customerId() != null;
        boolean brandNew = input.newCustomer() != null;
        if (existing == brandNew) {
            throw new BusinessRuleException("Provide exactly one of customerId or newCustomer");
        }
        if (existing) {
            return customers.findById(input.customerId())
                    .orElseThrow(() -> new NotFoundException("Customer", input.customerId()));
        }
        String email = Customer.normalizeEmail(input.newCustomer().email());
        if (customers.existsByEmail(email)) {
            throw new BusinessRuleException("A customer with email " + email + " already exists; use customerId");
        }
        return customers.save(new Customer(input.newCustomer().name(), email, Instant.now(clock)));
    }
}
