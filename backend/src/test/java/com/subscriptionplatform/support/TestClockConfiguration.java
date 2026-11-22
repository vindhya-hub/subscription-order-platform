package com.subscriptionplatform.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces the system clock with a {@link MutableClock} so "today" and message timings are deterministic. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

    /** Monday, 2 March 2026, 08:00 UTC. */
    public static final Instant START = Instant.parse("2026-03-02T08:00:00Z");
    public static final LocalDate TODAY = LocalDate.ofInstant(START, ZoneOffset.UTC);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(START);
    }
}
