package com.subscriptionplatform.subscription;

import com.subscriptionplatform.catalog.Plan;
import com.subscriptionplatform.common.BusinessRuleException;
import com.subscriptionplatform.customer.Customer;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A customer's standing order for a plan. The billing run turns each due cycle into an {@code Order}
 * and then advances {@link #getNextRunDate()}.
 */
@Entity
@Table(name = "subscriptions")
public class Subscription {

    public static final int MAX_QUANTITY = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private Plan plan;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Frequency frequency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SubscriptionStatus status;

    /** First run date; every later run date is derived from it (see {@link Frequency}). */
    @Column(name = "anchor_date", nullable = false)
    private LocalDate anchorDate;

    /** Index of the next cycle to bill (0 = anchor date). */
    @Column(name = "cycle_index", nullable = false)
    private int cycleIndex;

    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected Subscription() {
        // for JPA
    }

    public static Subscription start(Customer customer, Plan plan, int quantity, Frequency frequency,
                                     LocalDate firstRunDate, Instant now) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new BusinessRuleException("quantity must be between 1 and " + MAX_QUANTITY);
        }
        Subscription subscription = new Subscription();
        subscription.customer = customer;
        subscription.plan = plan;
        subscription.quantity = quantity;
        subscription.frequency = frequency;
        subscription.status = SubscriptionStatus.ACTIVE;
        subscription.anchorDate = firstRunDate;
        subscription.cycleIndex = 0;
        subscription.nextRunDate = firstRunDate;
        subscription.createdAt = now;
        subscription.updatedAt = now;
        return subscription;
    }

    /** True when the subscription should be billed by a run for {@code asOf}. */
    public boolean isDue(LocalDate asOf) {
        return status == SubscriptionStatus.ACTIVE && !nextRunDate.isAfter(asOf);
    }

    /** Moves the schedule to the next cycle after the current {@code nextRunDate} has been billed. */
    public LocalDate advanceSchedule(Instant now) {
        cycleIndex++;
        nextRunDate = frequency.runDate(anchorDate, cycleIndex);
        updatedAt = now;
        return nextRunDate;
    }

    /** ACTIVE -> PAUSED. Pausing an already paused subscription is a no-op. */
    public boolean pause(Instant now) {
        return switch (status) {
            case ACTIVE -> {
                status = SubscriptionStatus.PAUSED;
                updatedAt = now;
                yield true;
            }
            case PAUSED -> false;
            case CANCELLED -> throw new BusinessRuleException("Subscription " + id + " is cancelled and cannot be paused");
        };
    }

    /**
     * PAUSED -> ACTIVE. Cycles that fell due while the subscription was paused are skipped, not billed
     * retroactively: the schedule rolls forward to the first run date on or after {@code today}.
     */
    public boolean resume(LocalDate today, Instant now) {
        return switch (status) {
            case PAUSED -> {
                status = SubscriptionStatus.ACTIVE;
                while (nextRunDate.isBefore(today)) {
                    cycleIndex++;
                    nextRunDate = frequency.runDate(anchorDate, cycleIndex);
                }
                updatedAt = now;
                yield true;
            }
            case ACTIVE -> false;
            case CANCELLED -> throw new BusinessRuleException("Subscription " + id + " is cancelled and cannot be resumed");
        };
    }

    /** ACTIVE/PAUSED -> CANCELLED (terminal). Cancelling twice is a no-op. */
    public boolean cancel(Instant now) {
        if (status == SubscriptionStatus.CANCELLED) {
            return false;
        }
        status = SubscriptionStatus.CANCELLED;
        cancelledAt = now;
        updatedAt = now;
        return true;
    }

    /** Price of one delivery: unit price x quantity. */
    public BigDecimal getRecurringAmount() {
        return plan.getUnitPrice().multiply(BigDecimal.valueOf(quantity));
    }

    public Long getId() {
        return id;
    }

    public Customer getCustomer() {
        return customer;
    }

    public Plan getPlan() {
        return plan;
    }

    public int getQuantity() {
        return quantity;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public SubscriptionStatus getStatus() {
        return status;
    }

    public LocalDate getAnchorDate() {
        return anchorDate;
    }

    public int getCycleIndex() {
        return cycleIndex;
    }

    public LocalDate getNextRunDate() {
        return nextRunDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public long getVersion() {
        return version;
    }
}
