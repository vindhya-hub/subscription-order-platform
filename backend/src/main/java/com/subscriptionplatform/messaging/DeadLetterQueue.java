package com.subscriptionplatform.messaging;

import java.util.List;
import java.util.Set;

/** The dead-letter queue behind the order-processing queue's redrive policy. */
public interface DeadLetterQueue {

    /** Returns up to {@code maxMessages} dead letters without removing them. */
    List<DeadLetterMessage> peek(int maxMessages);

    /**
     * Moves dead letters back to the source queue, where they get a fresh receive count.
     *
     * @param messageIds the messages to move; an empty set moves everything
     * @return the messages that were moved
     */
    List<DeadLetterMessage> redrive(Set<String> messageIds);

    long size();
}
