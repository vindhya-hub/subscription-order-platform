package com.subscriptionplatform.operations;

import java.time.Instant;

/**
 * A dead-lettered OrderCreated message.
 *
 * @param orderId order referenced by the payload, or {@code null} if the payload is unreadable
 * @param sentAt  when the message was originally sent
 */
public record DeadLetter(String messageId, Long orderId, String payload, Instant sentAt) {
}
