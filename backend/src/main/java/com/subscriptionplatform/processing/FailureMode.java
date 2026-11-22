package com.subscriptionplatform.processing;

/** Deterministic failure injection for the simulated payment/fulfilment step. */
public enum FailureMode {
    /** Every attempt succeeds. */
    NONE,
    /** The first delivery of each message fails (gateway timeout); the retry succeeds. */
    TRANSIENT,
    /** Every delivery fails (gateway outage), so messages exhaust their receives and land in the DLQ. */
    PERMANENT
}
