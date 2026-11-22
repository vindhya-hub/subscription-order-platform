package com.subscriptionplatform.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** A clock tests can move forward, e.g. to let a message's visibility timeout or retry back-off expire. */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now;
    private final ZoneId zone;

    public MutableClock(Instant start) {
        this(new AtomicReference<>(start), ZoneOffset.UTC);
    }

    private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
        this.now = now;
        this.zone = zone;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    /** A view in another zone that shares (and moves with) this clock's instant. */
    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(now, zone);
    }

    @Override
    public Instant instant() {
        return now.get();
    }

    public void advance(Duration duration) {
        now.updateAndGet(instant -> instant.plus(duration));
    }

    public void set(Instant instant) {
        now.set(instant);
    }
}
