package com.college.pap.demo;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.model.FailureType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Review-1 / Layer-1 console demo.
 *
 * Simulates a flaky primary endpoint that:
 * <ul>
 *   <li>fails heavily between 14:00–15:00 UTC (solar / uplink window)</li>
 *   <li>fails in clusters of ~3–5 outside that window sometimes</li>
 * </ul>
 * Then prints the learned time-of-day rates and cluster state.
 */
public final class Layer1Demo {

    public static void main(String[] args) {
        System.out.println("=== Pattern-Aware Pool — Layer 1 Demo (Failure Memory) ===");
        System.out.println();

        FailureHistoryStore store = new FailureHistoryStore(500);
        PatternAnalyzer analyzer = new PatternAnalyzer(store);

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");

        LocalDate day = LocalDate.of(2026, 7, 26);
        seedPrimaryDayPattern(store, primary, day);
        seedBackupHealthy(store, backup, day);

        // Active cluster: last few primary attempts failed
        Instant clusterBase = day.atTime(16, 0).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            store.record(ConnectionAttempt.failure(
                    primary,
                    clusterBase.plusSeconds(i * 2L),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40 + i));
        }

                // Hourly learning is observe()-driven (not rebuilt from the ring buffer).
        for (EndpointId id : store.knownEndpoints()) {
            for (ConnectionAttempt a : store.getHistory(id)) {
                analyzer.observe(a);
            }
        }
        analyzer.analyzeAll();

        printProfile(analyzer.getProfile(primary));
        System.out.println();
        printProfile(analyzer.getProfile(backup));

        System.out.println();
        System.out.println("Interpretation for Review 1:");
        System.out.println("  - Layer 1 only learns and remembers. It does not route yet.");
        System.out.println("  - Layer 2 will turn these risk profiles into risk_score + failover.");
        System.out.println("  - Layer 3 will pre-warm backup before the 14:00 window opens.");
    }

    private static void seedPrimaryDayPattern(
            FailureHistoryStore store,
            EndpointId primary,
            LocalDate day) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        for (int hour = 0; hour < 24; hour++) {
            int samples = 12;
            for (int i = 0; i < samples; i++) {
                Instant ts = day.atTime(hour, rng.nextInt(60), rng.nextInt(60))
                        .toInstant(ZoneOffset.UTC);
                boolean inBadWindow = hour == 14;
                boolean fail;
                if (inBadWindow) {
                    fail = rng.nextDouble() < 0.75;
                } else {
                    // mostly healthy, occasional isolated failure
                    fail = rng.nextDouble() < 0.05;
                }

                if (fail) {
                    store.record(ConnectionAttempt.failure(
                            primary,
                            ts,
                            AttemptOutcome.TIMEOUT,
                            FailureType.TIMEOUT,
                            200 + rng.nextInt(300)));
                } else {
                    store.record(ConnectionAttempt.success(primary, ts, 5 + rng.nextInt(20)));
                }
            }
        }

        // Inject a few historical failure clusters around 10:00
        Instant burst = day.atTime(10, 30).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) {
            store.record(ConnectionAttempt.failure(
                    primary,
                    burst.plusSeconds(i),
                    AttemptOutcome.FAILURE,
                    FailureType.SSL_RESET,
                    15));
        }
        store.record(ConnectionAttempt.success(primary, burst.plusSeconds(10), 8));
    }

    private static void seedBackupHealthy(
            FailureHistoryStore store,
            EndpointId backup,
            LocalDate day) {
        for (int hour = 0; hour < 24; hour += 2) {
            for (int i = 0; i < 5; i++) {
                Instant ts = day.atTime(hour, i * 5).toInstant(ZoneOffset.UTC);
                store.record(ConnectionAttempt.success(backup, ts, 6));
            }
        }
    }

    private static void printProfile(EndpointRiskProfile profile) {
        System.out.println("Endpoint : " + profile.endpointId());
        System.out.println("Samples  : " + profile.sampleCount());
        System.out.printf("Recent failure rate : %.1f%%%n", profile.recentFailureRate() * 100);
        System.out.println("Cluster  : " + profile.clusterState());
        System.out.printf("Cluster penalty     : %.2f%n", profile.clusterState().clusterPenalty());
        System.out.println("Hourly failure rates (UTC) where elevated:");
        double[] rates = profile.hourlyFailureRates();
        boolean any = false;
        for (int h = 0; h < 24; h++) {
            if (rates[h] >= 0.15) {
                System.out.printf("  %02d:00  %.0f%%%n", h, rates[h] * 100);
                any = true;
            }
        }
        if (!any) {
            System.out.println("  (none elevated — endpoint looks stable)");
        }
    }
}
