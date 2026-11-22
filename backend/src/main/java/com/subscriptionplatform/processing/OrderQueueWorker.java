package com.subscriptionplatform.processing;

import com.subscriptionplatform.messaging.MessageQueue;
import com.subscriptionplatform.messaging.MessagingProperties;
import com.subscriptionplatform.messaging.ReceivedMessage;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One receive-process-acknowledge cycle against the order queue. {@link OrderQueueConsumer} calls it in a
 * loop; tests call {@link #pollOnce(Duration)} directly for deterministic, step-by-step control.
 */
@Component
public class OrderQueueWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderQueueWorker.class);

    private final MessageQueue queue;
    private final OrderMessageHandler handler;
    private final MessagingProperties properties;

    public OrderQueueWorker(MessageQueue queue, OrderMessageHandler handler, MessagingProperties properties) {
        this.queue = queue;
        this.handler = handler;
        this.properties = properties;
    }

    /**
     * Receives one batch (waiting up to {@code waitTime}) and processes each message.
     *
     * @return number of messages received
     */
    public int pollOnce(Duration waitTime) {
        List<ReceivedMessage> messages = queue.receive(properties.consumer().batchSize(), waitTime);
        messages.forEach(this::process);
        return messages.size();
    }

    private void process(ReceivedMessage message) {
        try {
            switch (handler.handle(message, queue.maxReceiveCount())) {
                case ACKNOWLEDGE -> queue.acknowledge(message);
                case RETRY -> queue.retryAfter(message, backoff(message.receiveCount()));
                // Visible again right away; the broker moves it to the DLQ on the next receive.
                case DEAD_LETTER -> queue.retryAfter(message, Duration.ZERO);
            }
        } catch (RuntimeException e) {
            log.error("Unexpected error handling message {}; it will be redelivered after its visibility timeout",
                    message.messageId(), e);
        }
    }

    /** Linear back-off: base delay x receive count. */
    Duration backoff(int receiveCount) {
        return properties.retryBackoff().multipliedBy(Math.max(1, receiveCount));
    }
}
