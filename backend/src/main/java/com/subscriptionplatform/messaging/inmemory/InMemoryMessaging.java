package com.subscriptionplatform.messaging.inmemory;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the in-memory equivalent of the AWS setup in {@code localstack/init-aws.sh}:
 * topic {@code order-events} -> queue {@code order-processing} -> (redrive) {@code order-processing-dlq}.
 */
public final class InMemoryMessaging {

    private final InMemoryTopic topic;
    private final InMemoryQueue queue;
    private final InMemoryQueue deadLetterQueue;
    private final InMemoryDeadLetterQueue deadLetters;

    private InMemoryMessaging(InMemoryTopic topic, InMemoryQueue queue, InMemoryQueue deadLetterQueue) {
        this.topic = topic;
        this.queue = queue;
        this.deadLetterQueue = deadLetterQueue;
        this.deadLetters = new InMemoryDeadLetterQueue(deadLetterQueue, queue);
    }

    public static InMemoryMessaging create(Duration visibilityTimeout, int maxReceiveCount, Clock clock) {
        InMemoryQueue dlq = new InMemoryQueue("order-processing-dlq", visibilityTimeout, null, clock);
        InMemoryQueue queue = new InMemoryQueue("order-processing", visibilityTimeout,
                new InMemoryQueue.RedrivePolicy(dlq, maxReceiveCount), clock);
        InMemoryTopic topic = new InMemoryTopic("order-events");
        topic.subscribe(queue);
        return new InMemoryMessaging(topic, queue, dlq);
    }

    public InMemoryTopic topic() {
        return topic;
    }

    public InMemoryQueue queue() {
        return queue;
    }

    public InMemoryQueue deadLetterQueue() {
        return deadLetterQueue;
    }

    public InMemoryDeadLetterQueue deadLetters() {
        return deadLetters;
    }
}
