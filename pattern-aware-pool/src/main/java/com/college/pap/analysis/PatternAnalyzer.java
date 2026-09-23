package com.college.pap.analysis;

import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.ClusterState;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 1 — Pattern learning with incremental hourly stats.
 * First {@code minHourSamples} observations use a plain mean (Laplace only while
 * still under that threshold); afterwards EWMA with alpha 0.35.
 *
 * <p>Hourly counters are updated only by {@link #observe}; they are never rebuilt
 * from the ring buffer, so learning survives capacity eviction.
 */
public final class PatternAnalyzer {

    public static final double DEFAULT_EWMA_ALPHA = 0.35;
    public static final int DEFAULT_MIN_HOUR_SAMPLES = 5;

    private final double ewmaAlpha;
    private final int recentWindow;
    private final int clusterThreshold;
    private final int minHourSamples;
    private final ZoneId zoneId;
    private final Clock clock;

    private final FailureHistoryStore historyStore;
    private final Map<EndpointId, EndpointRiskProfile> profiles = new ConcurrentHashMap<>();
    private final Map<EndpointId, HourlyStats> counters = new ConcurrentHashMap<>();

    public PatternAnalyzer(FailureHistoryStore historyStore) {
        this(historyStore, DEFAULT_EWMA_ALPHA, 20, 2, ZoneId.systemDefault());
    }

    public PatternAnalyzer(
            FailureHistoryStore historyStore,
            double ewmaAlpha,
            int recentWindow,
            int clusterThreshold,
            ZoneId zoneId) {
        this(historyStore, ewmaAlpha, recentWindow, clusterThreshold, DEFAULT_MIN_HOUR_SAMPLES, zoneId,
                Clock.system(zoneId));
    }

    public PatternAnalyzer(
            FailureHistoryStore historyStore,
            double ewmaAlpha,
            int recentWindow,
            int clusterThreshold,
            int minHourSamples,
            ZoneId zoneId) {
        this(historyStore, ewmaAlpha, recentWindow, clusterThreshold, minHourSamples, zoneId,
                Clock.system(zoneId));
    }

    public PatternAnalyzer(
            FailureHistoryStore historyStore,
            double ewmaAlpha,
            int recentWindow,
            int clusterThreshold,
            ZoneId zoneId,
            Clock clock) {
        this(historyStore, ewmaAlpha, recentWindow, clusterThreshold, DEFAULT_MIN_HOUR_SAMPLES, zoneId, clock);
    }

    public PatternAnalyzer(
            FailureHistoryStore historyStore,
            double ewmaAlpha,
            int recentWindow,
            int clusterThreshold,
            int minHourSamples,
            ZoneId zoneId,
            Clock clock) {
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        if (ewmaAlpha <= 0.0 || ewmaAlpha > 1.0) {
            throw new IllegalArgumentException("ewmaAlpha must be in (0, 1]");
        }
        if (recentWindow < 1) {
            throw new IllegalArgumentException("recentWindow must be >= 1");
        }
        if (clusterThreshold < 1) {
            throw new IllegalArgumentException("clusterThreshold must be >= 1");
        }
        if (minHourSamples < 1) {
            throw new IllegalArgumentException("minHourSamples must be >= 1");
        }
        this.ewmaAlpha = ewmaAlpha;
        this.recentWindow = recentWindow;
        this.clusterThreshold = clusterThreshold;
        this.minHourSamples = minHourSamples;
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public Clock clock() {
        return clock;
    }

    /** Call exactly once per recorded attempt so hourly counters stay fresh. */
    public void observe(ConnectionAttempt attempt) {
        Objects.requireNonNull(attempt);
        int hour = attempt.timestamp().atZone(zoneId).getHour();
        HourlyStats c = counters.computeIfAbsent(attempt.endpointId(), id -> new HourlyStats());
        c.record(hour, attempt.isFailure(), ewmaAlpha, minHourSamples);
    }

    public Map<EndpointId, EndpointRiskProfile> analyzeAll() {
        Map<EndpointId, EndpointRiskProfile> snapshot = new HashMap<>();
        for (EndpointId endpointId : historyStore.knownEndpoints()) {
            snapshot.put(endpointId, analyze(endpointId));
        }
        return Map.copyOf(snapshot);
    }

    public EndpointRiskProfile analyze(EndpointId endpointId) {
        Objects.requireNonNull(endpointId, "endpointId");
        List<ConnectionAttempt> history = historyStore.getHistory(endpointId);
        // Hourly stats come only from observe() — never rebuilt from the ring buffer.
        EndpointRiskProfile profile = buildProfile(endpointId, history, clock.instant());
        profiles.put(endpointId, profile);
        return profile;
    }

    public EndpointRiskProfile getProfile(EndpointId endpointId) {
        return profiles.get(endpointId);
    }

    public Map<EndpointId, EndpointRiskProfile> getAllProfiles() {
        return Map.copyOf(profiles);
    }

    private EndpointRiskProfile buildProfile(
            EndpointId endpointId,
            List<ConnectionAttempt> history,
            Instant computedAt) {
        HourlyStats c = counters.getOrDefault(endpointId, new HourlyStats());
        double[] hourlyRates = new double[24];
        double[] hourlyPlainRates = new double[24];
        int[] hourlySamples = new int[24];
        for (int h = 0; h < 24; h++) {
            hourlySamples[h] = c.samples[h];
            hourlyRates[h] = c.rate(h);
            hourlyPlainRates[h] = c.plainRate(h);
        }

        double recentRate = computeRecentFailureRate(history);
        ClusterState clusterState = detectClusters(history);
        return new EndpointRiskProfile(
                endpointId,
                hourlyRates,
                hourlyPlainRates,
                hourlySamples,
                recentRate,
                clusterState,
                computedAt,
                history.size());
    }

    private double computeRecentFailureRate(List<ConnectionAttempt> history) {
        if (history.isEmpty()) {
            return 0.0;
        }
        int from = Math.max(0, history.size() - recentWindow);
        int failures = 0;
        int total = 0;
        for (int i = from; i < history.size(); i++) {
            total++;
            if (history.get(i).isFailure()) {
                failures++;
            }
        }
        if (total == 0) {
            return 0.0;
        }
        // Plain rate once we have enough evidence; Laplace only while sparse.
        if (total < minHourSamples) {
            return (failures + 1.0) / (total + 2.0);
        }
        return (double) failures / total;
    }

    private ClusterState detectClusters(List<ConnectionAttempt> history) {
        if (history.isEmpty()) {
            return ClusterState.idle();
        }
        int completedRuns = 0;
        int completedRunLengthSum = 0;
        int runInProgress = 0;
        for (ConnectionAttempt attempt : history) {
            if (attempt.isFailure()) {
                runInProgress++;
            } else if (runInProgress > 0) {
                completedRuns++;
                completedRunLengthSum += runInProgress;
                runInProgress = 0;
            }
        }
        int currentRun = runInProgress;
        double averageRun = completedRuns == 0
                ? currentRun
                : (double) completedRunLengthSum / completedRuns;
        if (completedRuns == 0 && currentRun > 0) {
            averageRun = currentRun;
        }
        boolean inCluster = currentRun >= clusterThreshold;
        return new ClusterState(currentRun, averageRun, inCluster);
    }

    /**
     * Incremental per-hour stats: sample/failure counts plus EWMA after the
     * warm-up plain-mean window.
     */
    static final class HourlyStats {
        final int[] samples = new int[24];
        final int[] failures = new int[24];
        final double[] ewma = new double[24];
        final boolean[] ewmaActive = new boolean[24];

        void record(int hour, boolean failure, double alpha, int minHourSamples) {
            samples[hour]++;
            if (failure) {
                failures[hour]++;
            }
            double observation = failure ? 1.0 : 0.0;
            int n = samples[hour];
            if (n < minHourSamples) {
                // Sparse evidence: Laplace only for the warm-up window.
                ewma[hour] = (failures[hour] + 1.0) / (n + 2.0);
                ewmaActive[hour] = false;
            } else if (n == minHourSamples) {
                // Switch to plain mean of the first minHourSamples observations.
                ewma[hour] = (double) failures[hour] / n;
                ewmaActive[hour] = true;
            } else {
                ewma[hour] = alpha * observation + (1.0 - alpha) * ewma[hour];
                ewmaActive[hour] = true;
            }
        }

        double rate(int hour) {
            if (samples[hour] == 0) {
                return 0.0;
            }
            return ewma[hour];
        }

        double plainRate(int hour) {
            if (samples[hour] == 0) {
                return 0.0;
            }
            return (double) failures[hour] / samples[hour];
        }
    }
}
