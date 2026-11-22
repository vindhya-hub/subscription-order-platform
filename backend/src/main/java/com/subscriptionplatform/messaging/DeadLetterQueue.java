package com.subscriptionplatform.messaging;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** The dead-letter queue behind the order-processing queue's redrive policy. */
public interface DeadLetterQueue {

    /** Returns up to {@code maxMessages} dead letters without removing them. */
    List<DeadLetterMessage> peek(int maxMessages);

    /**
     * Moves dead letters back to the source queue, where they get a fresh receive count.
     *
     * @param messageIds   the messages to move; an empty set moves everything
     * @param beforeResend called for each message right before it is sent back, so state the consumer depends on
     *                     can be reset before the message can possibly be received again; if it throws, the
     *                     replay stops and that message stays in the dead-letter queue
     * @return the messages that were moved
     */
    List<DeadLetterMessage> redrive(Set<String> messageIds, Consumer<DeadLetterMessage> beforeResend);

    default List<DeadLetterMessage> redrive(Set<String> messageIds) {
        return redrive(messageIds, message -> {
        });
    }

    long size();
}
