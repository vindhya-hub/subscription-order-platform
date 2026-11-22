package com.subscriptionplatform.messaging;

import java.time.Instant;
import java.util.Map;

/**
 * A message handed to a consumer.
 *
 * @param receiptHandle handle for this particular receive; needed to acknowledge or retry
 * @param receiveCount  how many times the message has been received, including this time
 */
public record ReceivedMessage(String messageId, String receiptHandle, String body, Map<String, String> attributes,
                              int receiveCount, Instant sentAt) {

    public ReceivedMessage {
        attributes = Map.copyOf(attributes);
    }
}
