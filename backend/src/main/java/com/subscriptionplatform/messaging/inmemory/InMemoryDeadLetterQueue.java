package com.subscriptionplatform.messaging.inmemory;

import com.subscriptionplatform.messaging.DeadLetterMessage;
import com.subscriptionplatform.messaging.DeadLetterQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
    public List<DeadLetterMessage> redrive(Set<String> messageIds, Consumer<DeadLetterMessage> beforeResend) {
        List<DeadLetterMessage> moved = new ArrayList<>();
        for (InMemoryQueue.StoredMessage message : deadLetters.snapshot(Integer.MAX_VALUE)) {
            if (!messageIds.isEmpty() && !messageIds.contains(message.messageId())) {
                continue;
            }
            DeadLetterMessage deadLetter = toDeadLetter(message);
            beforeResend.accept(deadLetter);
            // Skip it if a concurrent replay took it first.
            if (deadLetters.remove(message.messageId())) {
                // Re-sent as a new message, so it starts again with a receive count of zero (as with SQS).
                source.send(message.body(), message.attributes());
                moved.add(deadLetter);
            }
        }
        return moved;
    }

    @Override
    public long size() {
        return deadLetters.size();
    }

    private static DeadLetterMessage toDeadLetter(InMemoryQueue.StoredMessage message) {
        return new DeadLetterMessage(message.messageId(), message.body(), message.attributes(), message.sentAt());
    }
}
