package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flaky endpoint simulator for the three motivating patterns:
 * time-of-day degradation, burst clustering, and threshold-triggered resets.
 */
public final class FlakyEndpointConnector implements EndpointConnector {

    public static final class PatternConfig {
        public final int badHour;
        public final double badHourFailRate;
        public final double baselineFailRate;
        public final int burstSize;
        public final double burstTriggerChance;
        public final int resetAfterSuccesses;
        public final long connectLatencyMs;
        public final long failLatencyMs;

        public PatternConfig(
                int badHour,
                double badHourFailRate,
                double baselineFailRate,
                int burstSize,
                double burstTriggerChance,
                int resetAfterSuccesses,
                long connectLatencyMs,
                long failLatencyMs) {
            this.badHour = badHour;
            this.badHourFailRate = badHourFailRate;
            this.baselineFailRate = baselineFailRate;
            this.burstSize = burstSize;
            this.burstTriggerChance = burstTriggerChance;
            this.resetAfterSuccesses = resetAfterSuccesses;
            this.connectLatencyMs = connectLatencyMs;
            this.failLatencyMs = failLatencyMs;
        }

        public static PatternConfig primaryFlaky() {
            return new PatternConfig(14, 0.85, 0.03, 4, 0.04, 47, 25, 80);
        }

        public static PatternConfig healthyBackup() {
            return new PatternConfig(-1, 0.0, 0.01, 0, 0.0, 0, 20, 40);
        }
    }

    private final EndpointId endpointId;
    private final PatternConfig config;
    private final Clock clock;
    private final boolean sleepEnabled;
    private final AtomicInteger successStreak = new AtomicInteger();
    private final AtomicInteger remainingBurst = new AtomicInteger();

    public FlakyEndpointConnector(EndpointId endpointId, PatternConfig config) {
        this(endpointId, config, Clock.systemUTC(), true);
    }

    public FlakyEndpointConnector(EndpointId endpointId, PatternConfig config, Clock clock, boolean sleepEnabled) {
        this.endpointId = Objects.requireNonNull(endpointId);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.sleepEnabled = sleepEnabled;
    }

    public static FlakyEndpointConnector forTests(EndpointId id, PatternConfig config, Clock clock) {
        return new FlakyEndpointConnector(id, config, clock, false);
    }

    @Override
    public EndpointId endpointId() {
        return endpointId;
    }

    @Override
    public PapConnection connect() throws ConnectionFailedException {
        Instant now = clock.instant();
        int hour = now.atZone(ZoneOffset.UTC).getHour();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (shouldFail(hour, rng)) {
            pause(config.failLatencyMs);
            throw new ConnectionFailedException(
                    "simulated failure on " + endpointId + " at hour " + hour,
                    hour == config.badHour ? FailureType.TIMEOUT : FailureType.NETWORK_UNREACHABLE);
        }

        pause(config.connectLatencyMs);
        successStreak.incrementAndGet();
        Object handle = "sim://" + endpointId + "/" + System.nanoTime();
        return new PapConnection(endpointId, handle, () -> {}, false);
    }

    private boolean shouldFail(int hour, ThreadLocalRandom rng) {
        if (config.resetAfterSuccesses > 0 && successStreak.get() >= config.resetAfterSuccesses) {
            successStreak.set(0);
            return true;
        }
        if (remainingBurst.get() > 0) {
            remainingBurst.decrementAndGet();
            return true;
        }
        if (config.badHour >= 0 && hour == config.badHour) {
            return rng.nextDouble() < config.badHourFailRate;
        }
        if (config.burstSize > 0 && rng.nextDouble() < config.burstTriggerChance) {
            remainingBurst.set(Math.max(0, config.burstSize - 1));
            return true;
        }
        return rng.nextDouble() < config.baselineFailRate;
    }

    private void pause(long ms) {
        if (!sleepEnabled || ms <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(ms, 15));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
