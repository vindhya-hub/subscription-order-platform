package com.subscriptionplatform.messaging.inmemory;

import com.subscriptionplatform.messaging.EventPublisher;
import com.subscriptionplatform.messaging.OutboundMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stand-in for an SNS topic: every published message is fanned out to each subscribed queue,
 * like an SNS-to-SQS subscription with raw message delivery (body and attributes are copied as-is).
 */
public class InMemoryTopic implements EventPublisher {

    private final String name;
    private final List<InMemoryQueue> subscribers = new CopyOnWriteArrayList<>();

    public InMemoryTopic(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void subscribe(InMemoryQueue queue) {
        subscribers.add(queue);
    }

    @Override
    public String publish(OutboundMessage message) {
        subscribers.forEach(queue -> queue.send(message.body(), message.attributes()));
        return UUID.randomUUID().toString();
    }
}
