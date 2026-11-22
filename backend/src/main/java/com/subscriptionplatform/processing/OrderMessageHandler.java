package com.subscriptionplatform.processing;

import com.subscriptionplatform.common.NotFoundException;
import com.subscriptionplatform.events.OrderCreatedEvent;
import com.subscriptionplatform.events.OrderEventCodec;
import com.subscriptionplatform.messaging.ReceivedMessage;
import com.subscriptionplatform.order.OrderService;
import com.subscriptionplatform.order.OrderSnapshot;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes one OrderCreated message and decides what should happen to it. Order status changes are
 * committed before the decision is returned, so the database always reflects the latest attempt.
 */
@Component
public class OrderMessageHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderMessageHandler.class);

    /** What to do with the message after handling it. */
    public enum Disposition {
        /** Done (or a duplicate of a finished order): delete the message. */
        ACKNOWLEDGE,
        /** Failed with receives left: make it visible again after a back-off. */
        RETRY,
        /** Failed on its last allowed receive: release it so the redrive policy moves it to the DLQ. */
        DEAD_LETTER
    }

    private final OrderEventCodec codec;
    private final OrderService orders;
    private final FulfillmentSimulator simulator;

    public OrderMessageHandler(OrderEventCodec codec, OrderService orders, FulfillmentSimulator simulator) {
        this.codec = codec;
        this.orders = orders;
        this.simulator = simulator;
    }

    public Disposition handle(ReceivedMessage message, int maxReceiveCount) {
        int delivery = message.receiveCount();
        boolean lastDelivery = delivery >= maxReceiveCount;

        OrderCreatedEvent event;
        try {
            event = codec.decode(message.body());
        } catch (IllegalArgumentException e) {
            log.warn("Unreadable message {} (delivery {}/{}): {}", message.messageId(), delivery, maxReceiveCount, e.getMessage());
            return lastDelivery ? Disposition.DEAD_LETTER : Disposition.RETRY;
        }

        long orderId = event.orderId();
        Optional<OrderSnapshot> attempt;
        try {
            attempt = orders.startAttempt(orderId);
        } catch (NotFoundException e) {
            log.warn("Message {} references unknown order {} (delivery {}/{})", message.messageId(), orderId, delivery, maxReceiveCount);
            return lastDelivery ? Disposition.DEAD_LETTER : Disposition.RETRY;
        }
        if (attempt.isEmpty()) {
            log.info("Order {} is already fulfilled; dropping duplicate message {}", orderId, message.messageId());
            return Disposition.ACKNOWLEDGE;
        }

        try {
            simulator.process(attempt.get(), delivery);
        } catch (ProcessingFailedException e) {
            if (lastDelivery) {
                orders.markFailed(orderId, e.getMessage());
                log.warn("Order {} failed on its last delivery ({}/{}), dead-lettering: {}", orderId, delivery, maxReceiveCount, e.getMessage());
                return Disposition.DEAD_LETTER;
            }
            orders.markRetryPending(orderId, e.getMessage());
            log.info("Order {} failed on delivery {}/{}, will retry: {}", orderId, delivery, maxReceiveCount, e.getMessage());
            return Disposition.RETRY;
        }

        orders.markFulfilled(orderId);
        log.info("Order {} fulfilled on delivery {}", orderId, delivery);
        return Disposition.ACKNOWLEDGE;
    }
}
