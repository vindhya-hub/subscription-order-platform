package com.subscriptionplatform.messaging;

import java.time.Duration;
import java.util.List;

/**
 * The order-processing work queue (an SQS queue subscribed to the topic in AWS).
 *
 * <p>Semantics follow SQS: a received message stays invisible for the visibility timeout, it is only
 * removed when {@link #acknowledge acknowledged}, and once it has been received more than
 * {@link #maxReceiveCount()} times the broker moves it to the dead-letter queue.
 */
public interface MessageQueue {

    /** Receives up to {@code maxMessages}, waiting up to {@code waitTime} for at least one (long polling). */
    List<ReceivedMessage> receive(int maxMessages, Duration waitTime);

    /** Deletes a successfully processed message. */
    void acknowledge(ReceivedMessage message);

    /** Leaves the message on the queue and makes it visible again after {@code delay}. */
    void retryAfter(ReceivedMessage message, Duration delay);

    /** Receives allowed before the redrive policy moves a message to the dead-letter queue. */
    int maxReceiveCount();

    QueueDepth depth();
}
