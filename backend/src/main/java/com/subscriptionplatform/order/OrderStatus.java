package com.subscriptionplatform.order;

/**
 * Order lifecycle.
 *
 * <pre>
 * PENDING --(consumer receives)--> PROCESSING --(success)--> FULFILLED
 *    ^                                  |
 *    +-----(failure, retries left)------+
 *                                       +--(failure, last receive)--> FAILED --(DLQ replay)--> PENDING
 * </pre>
 */
public enum OrderStatus {
    PENDING,
    PROCESSING,
    FULFILLED,
    FAILED
}
