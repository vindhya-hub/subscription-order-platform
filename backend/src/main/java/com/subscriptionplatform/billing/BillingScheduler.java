package com.subscriptionplatform.billing;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the billing cycle on the configured cron schedule (daily at 06:00 UTC by default). */
@Component
@ConditionalOnProperty(name = "app.billing.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class BillingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BillingScheduler.class);

    private final BillingService billingService;
    private final BillingProperties properties;
    private final Clock clock;

    public BillingScheduler(BillingService billingService, BillingProperties properties, Clock clock) {
        this.billingService = billingService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.billing.scheduler.cron:0 0 6 * * *}", zone = "${app.billing.scheduler.zone:UTC}")
    public void runScheduledBilling() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(properties.scheduler().zone())));
        BillingRunResult result = billingService.runBillingCycle(today);
        log.info("Scheduled billing run for {} created {} order(s)", today, result.ordersCreated());
    }
}
