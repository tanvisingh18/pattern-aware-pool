package com.college.pap.util;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable clock for deterministic experiments (set hour 09 vs 14 without waiting). */
public final class MutableClock extends Clock {
    private final ZoneId zone;
    private final AtomicReference<Instant> instant;

    public MutableClock(Instant initial, ZoneId zone) {
        this.zone = zone;
        this.instant = new AtomicReference<>(initial);
    }

    public static MutableClock utc(Instant initial) {
        return new MutableClock(initial, ZoneId.of("UTC"));
    }

    public void set(Instant instant) {
        this.instant.set(instant);
    }

    public void advance(java.time.Duration duration) {
        instant.updateAndGet(i -> i.plus(duration));
    }

    public void advanceSeconds(long seconds) {
        advance(java.time.Duration.ofSeconds(seconds));
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(instant.get(), zone);
    }

    @Override
    public Instant instant() {
        return instant.get();
    }
}
