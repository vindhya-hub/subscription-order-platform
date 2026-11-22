package com.subscriptionplatform.billing;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class BillingSchedulerTest {

    /** 23:30 UTC on 2 March is already 3 March in Tokyo. */
    private static final Clock LATE_EVENING_UTC = Clock.fixed(Instant.parse("2026-03-02T23:30:00Z"), ZoneOffset.UTC);

    private final BillingService billingService = mock(BillingService.class);

    @Test
    void billsForTodayInUtcByDefault() {
        LocalDate expected = LocalDate.of(2026, 3, 2);
        when(billingService.runBillingCycle(expected)).thenReturn(new BillingRunResult(expected, 0, List.of()));

        scheduler("UTC").runScheduledBilling();

        verify(billingService).runBillingCycle(expected);
    }

    @Test
    void billsForTodayInTheConfiguredZone() {
        LocalDate expected = LocalDate.of(2026, 3, 3);
        when(billingService.runBillingCycle(expected)).thenReturn(new BillingRunResult(expected, 0, List.of()));

        scheduler("Asia/Tokyo").runScheduledBilling();

        verify(billingService).runBillingCycle(expected);
    }

    private BillingScheduler scheduler(String zone) {
        BillingProperties properties = new BillingProperties(12, new BillingProperties.Scheduler(true, "0 0 6 * * *", zone));
        return new BillingScheduler(billingService, properties, LATE_EVENING_UTC);
    }
}
