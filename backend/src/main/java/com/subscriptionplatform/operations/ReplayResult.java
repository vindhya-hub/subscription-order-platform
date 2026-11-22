package com.subscriptionplatform.operations;

import java.util.List;

/**
 * @param replayed   number of dead letters moved back to the order queue
 * @param messageIds ids (in the DLQ) of the messages that were moved
 */
public record ReplayResult(int replayed, List<String> messageIds) {
}
