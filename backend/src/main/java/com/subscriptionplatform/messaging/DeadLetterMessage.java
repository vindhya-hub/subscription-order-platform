package com.subscriptionplatform.messaging;

import java.time.Instant;
import java.util.Map;

/**
 * A message sitting in the dead-letter queue.
 *
 * @param sentAt when the message was originally sent to the source queue
 */
public record DeadLetterMessage(String messageId, String body, Map<String, String> attributes, Instant sentAt) {

    public DeadLetterMessage {
        attributes = Map.copyOf(attributes);
    }
}
