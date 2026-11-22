package com.subscriptionplatform.operations;

import com.subscriptionplatform.events.OrderEventCodec;
import com.subscriptionplatform.messaging.DeadLetterMessage;
import com.subscriptionplatform.messaging.DeadLetterQueue;
import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.MessagingProperties;
import com.subscriptionplatform.messaging.QueueDepth;
import com.subscriptionplatform.order.OrderService;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Dead-letter inspection and replay, plus queue statistics. */
@Service
public class OperationsService {

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);
    private static final int MAX_DEAD_LETTERS = 100;

    private final MessageQueue queue;
    private final DeadLetterQueue deadLetterQueue;
    private final OrderService orders;
    private final OrderEventCodec codec;
    private final MessagingProperties properties;

    public OperationsService(MessageQueue queue, DeadLetterQueue deadLetterQueue, OrderService orders,
                             OrderEventCodec codec, MessagingProperties properties) {
        this.queue = queue;
        this.deadLetterQueue = deadLetterQueue;
        this.orders = orders;
        this.codec = codec;
        this.properties = properties;
    }

    public List<DeadLetter> deadLetters() {
        return deadLetterQueue.peek(MAX_DEAD_LETTERS).stream().map(this::toDeadLetter).toList();
    }

    /**
     * Moves dead letters back to the order queue and marks their orders PENDING again.
     *
     * @param messageIds which messages to replay; {@code null} replays all of them
     */
    public ReplayResult replay(Collection<String> messageIds) {
        if (messageIds != null && messageIds.isEmpty()) {
            return new ReplayResult(0, List.of());
        }
        List<DeadLetterMessage> moved = deadLetterQueue.redrive(messageIds == null ? Set.of() : Set.copyOf(messageIds));
        for (DeadLetterMessage message : moved) {
            Long orderId = orderIdOf(message);
            if (orderId == null) {
                continue;
            }
            try {
                orders.requeue(orderId);
            } catch (OptimisticLockingFailureException e) {
                // The consumer already picked the replayed message up; its status update wins.
                log.debug("Order {} changed while being requeued", orderId);
            }
        }
        log.info("Replayed {} dead letter(s)", moved.size());
        return new ReplayResult(moved.size(), moved.stream().map(DeadLetterMessage::messageId).toList());
    }

    public QueueStats queueStats() {
        QueueDepth depth = queue.depth();
        return new QueueStats(properties.provider().label(), depth.visible(), depth.inFlight(),
                deadLetterQueue.size(), queue.maxReceiveCount());
    }

    private DeadLetter toDeadLetter(DeadLetterMessage message) {
        return new DeadLetter(message.messageId(), orderIdOf(message), message.body(), message.sentAt());
    }

    private Long orderIdOf(DeadLetterMessage message) {
        try {
            return codec.decode(message.body()).orderId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
