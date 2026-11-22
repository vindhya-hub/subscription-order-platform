package com.subscriptionplatform.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code app.billing.*}
 *
 * @param maxCatchUpCycles upper bound on cycles billed for one subscription in a single run (protects
 *                         against a run far in the future creating hundreds of orders)
 * @param scheduler        the recurring billing job
 */
@ConfigurationProperties("app.billing")
public record BillingProperties(
        @DefaultValue("12") int maxCatchUpCycles,
        @DefaultValue Scheduler scheduler) {

    /**
     * @param enabled whether the {@code @Scheduled} billing job runs in this instance
     * @param cron    Spring cron expression (second minute hour day month weekday)
     * @param zone    time zone for the cron expression
     */
    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 0 6 * * *") String cron,
            @DefaultValue("UTC") String zone) {
    }
}
