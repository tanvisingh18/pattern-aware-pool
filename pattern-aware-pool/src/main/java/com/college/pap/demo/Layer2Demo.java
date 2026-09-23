package com.college.pap.demo;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskScore;
import com.college.pap.prediction.RiskWeights;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecider;
import com.college.pap.routing.RoutingDecision;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Layer-2 console demo — predictive rerouting.
 *
 * Seeds the same flaky primary pattern as Layer 1, then asks the router
 * for a connection at 09:00 (healthy) vs 14:20 (predicted bad window).
 */
public final class Layer2Demo {

    public static void main(String[] args) {
        System.out.println("=== Pattern-Aware Pool — Layer 2 Demo (Predictive Rerouting) ===");
        System.out.println();

        FailureHistoryStore store = new FailureHistoryStore(500);
        PatternAnalyzer analyzer = new PatternAnalyzer(store);
        PredictionEngine engine = new PredictionEngine(RiskWeights.defaults());

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);
        RoutingDecider decider = new RoutingDecider(registry, analyzer, engine, 0.55);

        LocalDate day = LocalDate.of(2026, 7, 26);
        seedPrimary(store, primary, day);
        seedBackup(store, backup, day);

        // Active cluster on primary — makes afternoon risk even higher
        Instant clusterBase = day.atTime(14, 10).toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 3; i++) {
            store.record(ConnectionAttempt.failure(
                    primary,
                    clusterBase.plusSeconds(i),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    30));
        }

                // Hourly learning is observe()-driven (not rebuilt from the ring buffer).
        for (EndpointId id : store.knownEndpoints()) {
            for (ConnectionAttempt a : store.getHistory(id)) {
                analyzer.observe(a);
            }
        }
        analyzer.analyzeAll();

        Instant morning = day.atTime(9, 15).toInstant(ZoneOffset.UTC);
        Instant badWindow = day.atTime(14, 20).toInstant(ZoneOffset.UTC);

        System.out.println("Weights: " + engine.weights());
        System.out.println("High-risk threshold: " + decider.highRiskThreshold());
        System.out.println();

        demonstrate(decider, primary, morning, "Scenario A — 09:15 UTC (normally healthy)");
        System.out.println();
        demonstrate(decider, primary, badWindow, "Scenario B — 14:20 UTC (learned bad window + cluster)");

        System.out.println();
        System.out.println("Interpretation:");
        System.out.println("  - Layer 2 does NOT wait for a live failure.");
        System.out.println("  - It scores endpoints from Layer-1 memory and pre-emptively fails over.");
        System.out.println("  - Layer 3 will pre-warm backup connections before 14:00 opens.");
    }

    private static void demonstrate(
            RoutingDecider decider,
            EndpointId requested,
            Instant at,
            String title) {
        RoutingDecision decision = decider.decide(requested, at);
        System.out.println(title);
        System.out.println("  Requested : " + decision.requested());
        for (RiskScore score : decision.allScores().values()) {
            System.out.println("  " + score);
        }
        System.out.println("  Selected  : " + decision.selected());
        System.out.println("  Reason    : " + decision.reason());
        System.out.println("  Rerouted  : " + decision.rerouted());
    }

    private static void seedPrimary(FailureHistoryStore store, EndpointId primary, LocalDate day) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int hour = 0; hour < 24; hour++) {
            for (int i = 0; i < 12; i++) {
                Instant ts = day.atTime(hour, rng.nextInt(60), rng.nextInt(60))
                        .toInstant(ZoneOffset.UTC);
                boolean fail = hour == 14 ? rng.nextDouble() < 0.80 : rng.nextDouble() < 0.04;
                if (fail) {
                    store.record(ConnectionAttempt.failure(
                            primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 250));
                } else {
                    store.record(ConnectionAttempt.success(primary, ts, 8));
                }
            }
        }
    }

    private static void seedBackup(FailureHistoryStore store, EndpointId backup, LocalDate day) {
        for (int hour = 0; hour < 24; hour += 2) {
            for (int i = 0; i < 6; i++) {
                Instant ts = day.atTime(hour, i * 5).toInstant(ZoneOffset.UTC);
                store.record(ConnectionAttempt.success(backup, ts, 7));
            }
        }
    }
}
