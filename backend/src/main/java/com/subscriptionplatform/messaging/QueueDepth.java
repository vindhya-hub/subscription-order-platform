package com.subscriptionplatform.messaging;

/**
 * Approximate queue depth.
 *
 * @param visible  messages waiting to be received
 * @param inFlight messages received but not yet acknowledged (or waiting out a retry delay)
 */
public record QueueDepth(long visible, long inFlight) {
}
