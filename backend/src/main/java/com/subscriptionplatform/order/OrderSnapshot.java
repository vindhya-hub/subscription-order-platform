package com.subscriptionplatform.order;

import java.math.BigDecimal;

/** Detached view of an order handed to the (non-transactional) payment/fulfilment step. */
public record OrderSnapshot(long orderId, long subscriptionId, String planCode, int quantity,
                            BigDecimal amount, String currency, int attempts) {

    static OrderSnapshot of(Order order) {
        return new OrderSnapshot(order.getId(), order.getSubscription().getId(),
                order.getSubscription().getPlan().getCode(), order.getQuantity(), order.getAmount(),
                order.getCurrency(), order.getAttempts());
    }
}
