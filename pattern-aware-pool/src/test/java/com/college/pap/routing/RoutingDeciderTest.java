package com.college.pap.routing;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskWeights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingDeciderTest {

    private final EndpointId primary = new EndpointId("primary-db");
    private final EndpointId backup = new EndpointId("backup-db");

    private FailureHistoryStore store;
    private PatternAnalyzer analyzer;
    private RoutingDecider decider;

    @BeforeEach
    void setUp() {
        store = new FailureHistoryStore(300);
        analyzer = new PatternAnalyzer(store);
        PredictionEngine engine = new PredictionEngine(RiskWeights.defaults());
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);
        decider = new RoutingDecider(registry, analyzer, engine, 0.55);
    }

    @Test
    void usesPrimaryWhenHealthy() {
        LocalDate day = LocalDate.of(2026, 7, 26);
        for (int i = 0; i < 20; i++) {
            Instant ts = day.atTime(9, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.success(primary, ts, 5));
            store.record(ConnectionAttempt.success(backup, ts, 5));
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(9, 30).toInstant(ZoneOffset.UTC));

        assertEquals(primary, decision.selected());
        assertEquals(RoutingDecision.Reason.PRIMARY_OK, decision.reason());
        assertFalse(decision.rerouted());
    }

    @Test
    void preemptivelyFailsOverDuringBadWindow() {
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200));
        }
        for (int i = 0; i < 3; i++) {
            Instant ts = day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 40));
        }
        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.success(backup, ts, 6));
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(14, 20).toInstant(ZoneOffset.UTC));

        assertEquals(backup, decision.selected());
        assertEquals(RoutingDecision.Reason.PREEMPTIVE_FAILOVER, decision.reason());
        assertTrue(decision.rerouted());
    }

    @Test
    void degradedModePicksLeastBadWhenAllRisky() {
        LocalDate day = LocalDate.of(2026, 7, 26);

        // Both endpoints fail hard in the 14:00 window and end in active clusters.
        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200));
            store.record(ConnectionAttempt.failure(
                    backup, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200));
        }
        // Primary has a longer trailing cluster → higher risk than backup.
        for (int i = 0; i < 4; i++) {
            store.record(ConnectionAttempt.failure(
                    primary,
                    day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40));
        }
        for (int i = 0; i < 2; i++) {
            store.record(ConnectionAttempt.failure(
                    backup,
                    day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40));
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(14, 55).toInstant(ZoneOffset.UTC));

        assertEquals(RoutingDecision.Reason.DEGRADED_MODE, decision.reason());
        assertTrue(decision.allScores().get(primary).isHighRisk(0.55));
        assertTrue(decision.allScores().get(backup).isHighRisk(0.55));
        // Must pick the least-bad endpoint among the high-risk set.
        double selected = decision.selectedScore().score();
        assertTrue(selected <= decision.allScores().get(primary).score() + 1e-9);
        assertTrue(selected <= decision.allScores().get(backup).score() + 1e-9);
    }
}
