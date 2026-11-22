package com.subscriptionplatform.operations;

/**
 * Pipeline health at a glance.
 *
 * @param provider        {@code in-memory} or {@code aws}
 * @param visible         messages waiting in the order queue
 * @param inFlight        messages being processed or waiting out a retry delay
 * @param deadLetters     messages in the dead-letter queue
 * @param maxReceiveCount receives allowed before a message is dead-lettered
 */
public record QueueStats(String provider, long visible, long inFlight, long deadLetters, int maxReceiveCount) {
}
