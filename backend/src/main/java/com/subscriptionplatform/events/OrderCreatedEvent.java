package com.subscriptionplatform.events;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.subscriptionplatform.order.Order;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Published to the order-events topic for every order the billing run creates.
 *
 * <p>{@code amount} is written as a decimal string (e.g. {@code "33.00"}) so that no consumer, in any language,
 * reads money through a binary floating-point number.
 */
public record OrderCreatedEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        long orderId,
        long subscriptionId,
        long customerId,
        String planCode,
        LocalDate runDate,
        int quantity,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String currency) {

    public static final String TYPE = "OrderCreated";

    public static OrderCreatedEvent from(Order order, Instant occurredAt) {
        return new OrderCreatedEvent(
                UUID.randomUUID(),
                TYPE,
                occurredAt,
                order.getId(),
                order.getSubscription().getId(),
                order.getSubscription().getCustomer().getId(),
                order.getSubscription().getPlan().getCode(),
                order.getRunDate(),
                order.getQuantity(),
                order.getAmount(),
                order.getCurrency());
    }
}
