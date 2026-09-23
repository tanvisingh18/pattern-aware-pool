package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Flaky endpoint simulator for the three motivating patterns:
 * time-of-day degradation, burst clustering, and threshold-triggered resets.
 *
 * <p>Latency is recorded as a simulated metric ({@link #lastSimulatedLatencyMs()});
 * wall-clock sleep is disabled by default for tests/experiments.
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
        public final long badHourFailLatencyMs;

        public PatternConfig(
                int badHour,
                double badHourFailRate,
                double baselineFailRate,
                int burstSize,
                double burstTriggerChance,
                int resetAfterSuccesses,
                long connectLatencyMs,
                long failLatencyMs) {
            this(badHour, badHourFailRate, baselineFailRate, burstSize, burstTriggerChance,
                    resetAfterSuccesses, connectLatencyMs, failLatencyMs, 2000);
        }

        public PatternConfig(
                int badHour,
                double badHourFailRate,
                double baselineFailRate,
                int burstSize,
                double burstTriggerChance,
                int resetAfterSuccesses,
                long connectLatencyMs,
                long failLatencyMs,
                long badHourFailLatencyMs) {
            this.badHour = badHour;
            this.badHourFailRate = badHourFailRate;
            this.baselineFailRate = baselineFailRate;
            this.burstSize = burstSize;
            this.burstTriggerChance = burstTriggerChance;
            this.resetAfterSuccesses = resetAfterSuccesses;
            this.connectLatencyMs = connectLatencyMs;
            this.failLatencyMs = failLatencyMs;
            this.badHourFailLatencyMs = badHourFailLatencyMs;
        }

        public static PatternConfig primaryFlaky() {
            return new PatternConfig(14, 0.85, 0.03, 4, 0.04, 47, 25, 80, 2000);
        }

        /** Reset-only pattern for ResetPatternDetector tests (no TOD / burst noise). */
        public static PatternConfig resetOnly(int resetAfterSuccesses) {
            return new PatternConfig(-1, 0.0, 0.0, 0, 0.0, resetAfterSuccesses, 5, 40, 40);
        }

        public static PatternConfig healthyBackup() {
            return new PatternConfig(-1, 0.0, 0.01, 0, 0.0, 0, 20, 40, 40);
        }
    }

    private final EndpointId endpointId;
    private final PatternConfig config;
    private final Clock clock;
    private final boolean sleepEnabled;
    private final Random random;
    private final AtomicInteger successStreak = new AtomicInteger();
    private final AtomicInteger remainingBurst = new AtomicInteger();
    private final AtomicLong lastSimulatedLatencyMs = new AtomicLong();

    public FlakyEndpointConnector(EndpointId endpointId, PatternConfig config) {
        this(endpointId, config, Clock.systemUTC(), false, null);
    }

    public FlakyEndpointConnector(EndpointId endpointId, PatternConfig config, Clock clock, boolean sleepEnabled) {
        this(endpointId, config, clock, sleepEnabled, null);
    }

    public FlakyEndpointConnector(
            EndpointId endpointId,
            PatternConfig config,
            Clock clock,
            boolean sleepEnabled,
            Random random) {
        this.endpointId = Objects.requireNonNull(endpointId);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.sleepEnabled = sleepEnabled;
        this.random = random;
    }

    public static FlakyEndpointConnector forTests(EndpointId id, PatternConfig config, Clock clock) {
        return new FlakyEndpointConnector(id, config, clock, false, null);
    }

    public static FlakyEndpointConnector forTests(
            EndpointId id, PatternConfig config, Clock clock, Random random) {
        return new FlakyEndpointConnector(id, config, clock, false, Objects.requireNonNull(random));
    }

    public long lastSimulatedLatencyMs() {
        return lastSimulatedLatencyMs.get();
    }

    @Override
    public EndpointId endpointId() {
        return endpointId;
    }

    @Override
    public PapConnection connect() throws ConnectionFailedException {
        Instant now = clock.instant();
        int hour = now.atZone(ZoneOffset.UTC).getHour();
        Random rng = random != null ? random : ThreadLocalRandom.current();

        if (shouldFail(hour, rng)) {
            long latency = (config.badHour >= 0 && hour == config.badHour)
                    ? config.badHourFailLatencyMs
                    : config.failLatencyMs;
            lastSimulatedLatencyMs.set(latency);
            pause(latency);
            ConnectionFailedException ex = new ConnectionFailedException(
                    "simulated failure on " + endpointId + " at hour " + hour,
                    hour == config.badHour ? FailureType.TIMEOUT : FailureType.NETWORK_UNREACHABLE);
            ex.setSimulatedLatencyMs(latency);
            throw ex;
        }

        lastSimulatedLatencyMs.set(config.connectLatencyMs);
        pause(config.connectLatencyMs);
        successStreak.incrementAndGet();
        Object handle = "sim://" + endpointId + "/" + System.nanoTime();
        PapConnection conn = new PapConnection(endpointId, handle, () -> {}, false);
        conn.setLastSimulatedLatencyMs(config.connectLatencyMs);
        return conn;
    }

    private boolean shouldFail(int hour, Random rng) {
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
