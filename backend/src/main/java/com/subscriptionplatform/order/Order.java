package com.subscriptionplatform.order;

import com.subscriptionplatform.subscription.Subscription;
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
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One delivery of a subscription for a specific run date. The (subscription, runDate) pair is unique,
 * which is what makes the billing run idempotent even when two runs race.
 *
 * <p>The JPA entity name is {@code SubscriptionOrder} because {@code Order} is a reserved word in JPQL.
 */
@Entity(name = "SubscriptionOrder")
@Table(name = "orders", uniqueConstraints = @UniqueConstraint(
        name = "uk_orders_subscription_run_date", columnNames = {"subscription_id", "run_date"}))
public class Order {

    private static final int MAX_ERROR_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(name = "run_date", nullable = false)
    private LocalDate runDate;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OrderStatus status;

    /** Number of processing attempts across all deliveries, including DLQ replays. */
    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "fulfilled_at")
    private Instant fulfilledAt;

    @Version
    private long version;

    protected Order() {
        // for JPA
    }

    /** Creates the PENDING order for one billing cycle, snapshotting price and quantity. */
    public static Order forCycle(Subscription subscription, LocalDate runDate, Instant now) {
        Order order = new Order();
        order.subscription = subscription;
        order.runDate = runDate;
        order.quantity = subscription.getQuantity();
        order.unitPrice = subscription.getPlan().getUnitPrice();
        order.amount = order.unitPrice.multiply(BigDecimal.valueOf(order.quantity)).setScale(2, RoundingMode.HALF_EVEN);
        order.currency = subscription.getPlan().getCurrency();
        order.status = OrderStatus.PENDING;
        order.attempts = 0;
        order.createdAt = now;
        order.updatedAt = now;
        return order;
    }

    /** A consumer picked the order up. Valid from PENDING, FAILED (replay) or PROCESSING (redelivery). */
    public void startAttempt(Instant now) {
        if (status == OrderStatus.FULFILLED) {
            throw new IllegalStateException("Order " + id + " is already fulfilled");
        }
        status = OrderStatus.PROCESSING;
        attempts++;
        updatedAt = now;
    }

    public void markFulfilled(Instant now) {
        status = OrderStatus.FULFILLED;
        lastError = null;
        fulfilledAt = now;
        updatedAt = now;
    }

    /** The attempt failed but the message will be redelivered. */
    public void markRetryPending(String error, Instant now) {
        status = OrderStatus.PENDING;
        lastError = truncate(error);
        updatedAt = now;
    }

    /** The final delivery failed; the message is headed for the dead-letter queue. */
    public void markFailed(String error, Instant now) {
        status = OrderStatus.FAILED;
        lastError = truncate(error);
        updatedAt = now;
    }

    /** FAILED -> PENDING when its dead letter is replayed. Returns false when the order moved on already. */
    public boolean requeue(Instant now) {
        if (status != OrderStatus.FAILED) {
            return false;
        }
        status = OrderStatus.PENDING;
        updatedAt = now;
        return true;
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) {
            return error;
        }
        return error.substring(0, MAX_ERROR_LENGTH - 3) + "...";
    }

    public Long getId() {
        return id;
    }

    public Subscription getSubscription() {
        return subscription;
    }

    public LocalDate getRunDate() {
        return runDate;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getFulfilledAt() {
        return fulfilledAt;
    }

    public long getVersion() {
        return version;
    }
}
