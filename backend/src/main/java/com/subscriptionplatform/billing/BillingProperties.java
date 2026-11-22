package com.subscriptionplatform.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.billing.*}, mapped to environment variables in {@code application.yml}.
 *
 * @param maxCatchUpCycles upper bound on cycles billed for one subscription in a single run (protects
 *                         against a run far in the future creating hundreds of orders)
 * @param scheduler        the recurring billing job
 */
@ConfigurationProperties("app.billing")
public record BillingProperties(
        int maxCatchUpCycles,
        @DefaultValue Scheduler scheduler) {

    /**
     * @param enabled whether the {@code @Scheduled} billing job runs in this instance
     * @param cron    Spring cron expression (second minute hour day month weekday)
     * @param zone    time zone for the cron expression and for "today"
     */
    public record Scheduler(boolean enabled, String cron, String zone) {
    }
}
