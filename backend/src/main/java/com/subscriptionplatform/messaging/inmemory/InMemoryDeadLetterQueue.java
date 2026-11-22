package com.subscriptionplatform.messaging.inmemory;

import com.subscriptionplatform.messaging.DeadLetterMessage;
import com.subscriptionplatform.messaging.DeadLetterQueue;
import java.util.List;
import java.util.Set;

/** Dead-letter queue view over an {@link InMemoryQueue}, able to redrive messages to their source queue. */
public class InMemoryDeadLetterQueue implements DeadLetterQueue {

    private final InMemoryQueue deadLetters;
    private final InMemoryQueue source;

    public InMemoryDeadLetterQueue(InMemoryQueue deadLetters, InMemoryQueue source) {
        this.deadLetters = deadLetters;
        this.source = source;
    }

    @Override
    public List<DeadLetterMessage> peek(int maxMessages) {
        return deadLetters.snapshot(maxMessages).stream().map(InMemoryDeadLetterQueue::toDeadLetter).toList();
    }

    @Override
    public List<DeadLetterMessage> redrive(Set<String> messageIds) {
        List<InMemoryQueue.StoredMessage> moved =
                deadLetters.removeIf(message -> messageIds.isEmpty() || messageIds.contains(message.messageId()));
        // Re-sent as new messages, so each starts again with a receive count of zero (as with SQS SendMessage).
        moved.forEach(message -> source.send(message.body(), message.attributes()));
        return moved.stream().map(InMemoryDeadLetterQueue::toDeadLetter).toList();
    }

    @Override
    public long size() {
        return deadLetters.size();
    }

    private static DeadLetterMessage toDeadLetter(InMemoryQueue.StoredMessage message) {
        return new DeadLetterMessage(message.messageId(), message.body(), message.attributes(), message.sentAt());
    }
}
