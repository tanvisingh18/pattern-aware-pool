package com.college.pap.analysis;

import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.ClusterState;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 1 — Pattern learning with Laplace-smoothed hourly rates
 * and per-hour sample counts (so a single failure cannot mark an hour 100% bad).
 */
public final class PatternAnalyzer {

    private final double ewmaAlpha;
    private final int recentWindow;
    private final int clusterThreshold;
    private final ZoneId zoneId;

    private final FailureHistoryStore historyStore;
    private final Map<EndpointId, EndpointRiskProfile> profiles = new ConcurrentHashMap<>();
    /** Running per-hour success/failure counters (survive buffer eviction conceptually for rates). */
    private final Map<EndpointId, HourlyCounters> counters = new ConcurrentHashMap<>();

    public PatternAnalyzer(FailureHistoryStore historyStore) {
        this(historyStore, 0.35, 20, 2, ZoneId.systemDefault());
    }

    public PatternAnalyzer(
            FailureHistoryStore historyStore,
            double ewmaAlpha,
            int recentWindow,
            int clusterThreshold,
            ZoneId zoneId) {
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
        this.ewmaAlpha = ewmaAlpha;
        this.recentWindow = recentWindow;
        this.clusterThreshold = clusterThreshold;
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    /** Call when a new attempt is recorded so counters stay fresh. */
    public void observe(ConnectionAttempt attempt) {
        Objects.requireNonNull(attempt);
        int hour = attempt.timestamp().atZone(zoneId).getHour();
        HourlyCounters c = counters.computeIfAbsent(attempt.endpointId(), id -> new HourlyCounters());
        if (attempt.isFailure()) {
            c.failures[hour]++;
        } else {
            c.successes[hour]++;
        }
        c.total[hour]++;
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
        // Keep counters in sync if observe() wasn't called.
        for (ConnectionAttempt a : history) {
            // Rebuild from history for correctness when loading seeded data.
        }
        ensureCountersFromHistory(endpointId, history);
        EndpointRiskProfile profile = buildProfile(endpointId, history, Instant.now());
        profiles.put(endpointId, profile);
        return profile;
    }

    public EndpointRiskProfile getProfile(EndpointId endpointId) {
        return profiles.get(endpointId);
    }

    public Map<EndpointId, EndpointRiskProfile> getAllProfiles() {
        return Map.copyOf(profiles);
    }

    private void ensureCountersFromHistory(EndpointId endpointId, List<ConnectionAttempt> history) {
        HourlyCounters c = new HourlyCounters();
        for (ConnectionAttempt attempt : history) {
            int hour = attempt.timestamp().atZone(zoneId).getHour();
            if (attempt.isFailure()) {
                c.failures[hour]++;
            } else {
                c.successes[hour]++;
            }
            c.total[hour]++;
        }
        counters.put(endpointId, c);
    }

    private EndpointRiskProfile buildProfile(
            EndpointId endpointId,
            List<ConnectionAttempt> history,
            Instant computedAt) {
        HourlyCounters c = counters.getOrDefault(endpointId, new HourlyCounters());
        double[] hourlyRates = new double[24];
        int[] hourlySamples = new int[24];
        for (int h = 0; h < 24; h++) {
            hourlySamples[h] = c.total[h];
            // Laplace / additive smoothing: (failures + 1) / (samples + 2)
            // Prevents first failure from locking the hour at 100%.
            if (c.total[h] == 0) {
                hourlyRates[h] = 0.0;
            } else {
                hourlyRates[h] = (c.failures[h] + 1.0) / (c.total[h] + 2.0);
            }
        }
        // Blend with EWMA from chronological walk for recency inside the hour.
        double[] ewma = computeHourlyEwma(history);
        for (int h = 0; h < 24; h++) {
            if (c.total[h] > 0) {
                hourlyRates[h] = 0.5 * hourlyRates[h] + 0.5 * ewma[h];
            }
        }

        double recentRate = computeRecentFailureRate(history);
        ClusterState clusterState = detectClusters(history);
        return new EndpointRiskProfile(
                endpointId,
                hourlyRates,
                hourlySamples,
                recentRate,
                clusterState,
                computedAt,
                history.size());
    }

    private double[] computeHourlyEwma(List<ConnectionAttempt> history) {
        double[] rates = new double[24];
        boolean[] seen = new boolean[24];
        for (ConnectionAttempt attempt : history) {
            int hour = attempt.timestamp().atZone(zoneId).getHour();
            double observation = attempt.isFailure() ? 1.0 : 0.0;
            if (!seen[hour]) {
                // Start from Laplace prior rather than raw first observation.
                rates[hour] = 0.5 * observation + 0.5 * 0.5;
                seen[hour] = true;
            } else {
                rates[hour] = ewmaAlpha * observation + (1.0 - ewmaAlpha) * rates[hour];
            }
        }
        return rates;
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
        // Laplace on recent window too
        return (failures + 1.0) / (total + 2.0);
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

    private static final class HourlyCounters {
        final int[] successes = new int[24];
        final int[] failures = new int[24];
        final int[] total = new int[24];
    }
}
