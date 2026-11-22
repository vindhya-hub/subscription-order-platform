package com.subscriptionplatform.subscription;

import java.time.LocalDate;

/**
 * How often a subscription produces an order.
 *
 * <p>Run dates are always computed from the subscription's anchor date and a cycle index rather than
 * by repeatedly adding to the previous date. That keeps monthly schedules from drifting: a subscription
 * anchored on Jan 31 runs on Feb 28, then Mar 31, then Apr 30.
 */
public enum Frequency {
    WEEKLY,
    BIWEEKLY,
    MONTHLY;

    /** The run date of the given cycle (cycle 0 is the anchor date itself). */
    public LocalDate runDate(LocalDate anchorDate, int cycle) {
        if (cycle < 0) {
            throw new IllegalArgumentException("cycle must be >= 0");
        }
        return switch (this) {
            case WEEKLY -> anchorDate.plusWeeks(cycle);
            case BIWEEKLY -> anchorDate.plusWeeks(2L * cycle);
            case MONTHLY -> anchorDate.plusMonths(cycle);
        };
    }
}
